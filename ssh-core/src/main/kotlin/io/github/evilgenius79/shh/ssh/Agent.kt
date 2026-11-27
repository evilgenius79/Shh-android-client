package io.github.evilgenius79.shh.ssh

import com.hierynomus.sshj.key.KeyAlgorithms
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannelOpener
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/**
 * Agent forwarding: lets programs on the server (`ssh`, `git`, `scp`) authenticate with keys
 * that never leave this device. The server opens a channel per program; each request on it is
 * answered here. Listing the keys is harmless. Signing is what logs somebody in, so every
 * signature is put to [confirm] first.
 */
internal class AgentForwarder(
    connection: Connection,
    private val keys: () -> List<LoadedKey>,
    private val confirm: (SshPublicKey) -> Boolean,
) : AbstractForwardedChannelOpener(CHANNEL_TYPE, connection) {
    /** Loaded on first use: decrypting every stored key is wasted work if nothing asks. */
    // A key on a security key cannot be lent: signing with it takes the hardware and its owner.
    private val identities: List<LoadedKey> by lazy { keys().filter { it.securityKey == null } }

    private class AgentChannel(connection: Connection, recipient: Int, window: Long, maxPacket: Long) :
        AbstractForwardedChannel(connection, CHANNEL_TYPE, recipient, window, maxPacket, "", 0)

    override fun handleOpen(buf: SSHPacket) {
        val channel = try {
            AgentChannel(conn, buf.readUInt32AsInt(), buf.readUInt32(), buf.readUInt32())
        } catch (e: Buffer.BufferException) {
            throw ConnectionException(e)
        }
        callListener(ConnectListener { opened -> serve(opened) }, channel)
    }

    private fun serve(channel: Channel.Forwarded) {
        channel.confirm()
        try {
            val input = DataInputStream(channel.inputStream)
            val output = channel.outputStream
            while (true) {
                val length = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                if (length < 1 || length > MAX_MESSAGE) break
                val message = ByteArray(length)
                input.readFully(message)
                val reply = answer(message)
                output.write(Buffer.PlainBuffer().putUInt32(reply.size.toLong()).putRawBytes(reply).compactData)
                output.flush()
            }
        } catch (_: IOException) {
            // The program on the other side went away.
        } finally {
            runCatching { channel.close() }
        }
    }

    /** Answers one agent request. Anything not understood or not allowed is a plain failure. */
    internal fun answer(message: ByteArray): ByteArray = try {
        val request = Buffer.PlainBuffer(message)
        when (request.readByte()) {
            REQUEST_IDENTITIES -> {
                val offered = identities.flatMap { key -> listOfNotNull(key.certificate?.blob, key.publicKey.blob) }
                val reply = Buffer.PlainBuffer().putByte(IDENTITIES_ANSWER).putUInt32(offered.size.toLong())
                for (blob in offered) reply.putBytes(blob).putString("")
                reply.compactData
            }
            SIGN_REQUEST -> {
                val blob = request.readBytes()
                val data = request.readBytes()
                val flags = if (request.available() >= 4) request.readUInt32AsInt() else 0
                val key = identities.firstOrNull { it.publicKey.blob.contentEquals(blob) || it.certificate?.blob?.contentEquals(blob) == true }
                if (key == null || !confirm(key.publicKey)) FAILURE_REPLY else sign(key, data, flags)
            }
            else -> FAILURE_REPLY
        }
    } catch (_: Exception) {
        FAILURE_REPLY
    }

    private fun sign(key: LoadedKey, data: ByteArray, flags: Int): ByteArray {
        val algorithm = when (key.publicKey.type) {
            "ssh-ed25519" -> KeyAlgorithms.EdDSA25519()
            "ecdsa-sha2-nistp256" -> KeyAlgorithms.ECDSASHANistp256()
            "ecdsa-sha2-nistp384" -> KeyAlgorithms.ECDSASHANistp384()
            "ecdsa-sha2-nistp521" -> KeyAlgorithms.ECDSASHANistp521()
            // The requester says which hash it wants for RSA; without a wish it is the old SHA-1.
            "ssh-rsa" -> when {
                flags and RSA_SHA2_512 != 0 -> KeyAlgorithms.RSASHA512()
                flags and RSA_SHA2_256 != 0 -> KeyAlgorithms.RSASHA256()
                else -> KeyAlgorithms.SSHRSA()
            }
            else -> return FAILURE_REPLY
        }
        val signature = algorithm.create().newSignature()
        signature.initSign(key.keyPair.private)
        signature.update(data)
        val encoded = signature.encode(signature.sign())
        return Buffer.PlainBuffer().putByte(SIGN_RESPONSE).putSignature(signature.signatureName, encoded).compactData
    }

    internal companion object {
        const val CHANNEL_TYPE = "auth-agent@openssh.com"
        const val REQUEST_TYPE = "auth-agent-req@openssh.com"

        // Message numbers and flags of the agent protocol (draft-miller-ssh-agent).
        const val FAILURE: Byte = 5
        const val REQUEST_IDENTITIES: Byte = 11
        const val IDENTITIES_ANSWER: Byte = 12
        const val SIGN_REQUEST: Byte = 13
        const val SIGN_RESPONSE: Byte = 14
        const val RSA_SHA2_256 = 2
        const val RSA_SHA2_512 = 4

        /** No honest request comes near this; it stops a peer from making us allocate without end. */
        const val MAX_MESSAGE = 256 * 1024

        val FAILURE_REPLY = byteArrayOf(FAILURE)
    }
}
