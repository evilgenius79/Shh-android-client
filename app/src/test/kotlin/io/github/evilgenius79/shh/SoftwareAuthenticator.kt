package io.github.evilgenius79.shh

import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.Cbor
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A FIDO2 authenticator made of software, for tests: it answers the smart card commands a
 * security key gets over NFC and speaks CTAP2 inside them. It is written from the CTAP
 * specification rather than from the client library, so that the two agreeing means something:
 * the framing in APDUs, both ways of proving the PIN, and what gets signed are all done here
 * a second time.
 *
 * It keeps real private keys and signs with them, so that what comes out can be checked by
 * anything that checks signatures of security keys, an SSH server included.
 */
class SoftwareAuthenticator(
    /** The ways of proving the PIN it offers, the preferred one first. */
    private val pinProtocols: List<Int> = listOf(2, 1),
    /** The PIN that is set on it, if any. */
    var pin: String? = null,
    /** It insists on the PIN (or a fingerprint) for everything. */
    private val alwaysUv: Boolean = false,
    /** It has a fingerprint reader with a finger enrolled. */
    private val fingerprint: Boolean = false,
    /** It hands out PIN tokens with permissions, as authenticators since CTAP 2.1 do. */
    private val tokensWithPermissions: Boolean = true,
    /** It can make Ed25519 keys. Older security keys only make ECDSA ones. */
    private val makesEd25519: Boolean = true,
    /** It can guard a credential so that it is only used with the PIN ("credProtect"). */
    private val guardsCredentials: Boolean = true,
) : SmartCardConnection {
    class Credential(val id: ByteArray, val application: String, val ed25519: Boolean, val pair: KeyPair) {
        /** 3 when the key only uses this credential after checking the PIN or a fingerprint. */
        var protection = 1

        /** The public key as hardware reports it: 32 bytes for Ed25519, else the uncompressed point. */
        val point: ByteArray
            get() = if (ed25519) {
                pair.public.encoded.takeLast(32).toByteArray()
            } else {
                val w = (pair.public as ECPublicKey).w
                byteArrayOf(4) + coordinate(w.affineX) + coordinate(w.affineY)
            }
    }

    val credentials = ArrayList<Credential>()

    /** Whether someone touches the key when it waits for that. */
    var touched = true

    /** How often it says "waiting for the touch" before it answers, when the client can wait. */
    var keepAlives = 0

    var pinRetries = 8
    var signatures = 0
        private set

    /** The CTAP commands it was sent, by their number. */
    val commands = ArrayList<Int>()

    /** The options of the last request to sign, as the client sent them. */
    var lastOptions: Map<String, Any?> = emptyMap()
        private set

    /** Who the last key that was made is for, as the client sent it. */
    var lastUser: Map<String, Any?> = emptyMap()
        private set

    /** Set to make every command after selecting fail as if the key had been taken away. */
    var removed = false

    private val random = SecureRandom()
    private val aaguid = ByteArray(16).also { random.nextBytes(it) }
    private val agreement: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private var token: ByteArray? = null
    private var tokenPermissions = 0
    private var tokenApplication: String? = null
    private var wrongInARow = 0

    private var selected = false
    private val chained = ByteArrayOutputStream()
    private var remaining = ByteArray(0)
    private var heldBack: ByteArray? = null
    private var undo: () -> Unit = {}
    private var keepAlivesLeft = 0

    /** Makes a key on the authenticator the way `ssh-keygen -t ed25519-sk` would, without the ceremony. */
    fun addCredential(ed25519: Boolean, application: String = "ssh:", idLength: Int = 64): Credential {
        val pair = if (ed25519) {
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        } else {
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        }
        return Credential(ByteArray(idLength).also { random.nextBytes(it) }, application, ed25519, pair).also(credentials::add)
    }

    // ----- the smart card side -----

    override fun getTransport(): Transport = Transport.NFC

    override fun isExtendedLengthApduSupported(): Boolean = false

    override fun getAtr(): ByteArray = ByteArray(0)

    override fun close() = Unit

    override fun sendAndReceive(apdu: ByteArray): ByteArray {
        if (removed) throw java.io.IOException("Tag was lost")
        val cla = apdu[0].toInt() and 0xff
        val ins = apdu[1].toInt() and 0xff
        val p1 = apdu[2].toInt() and 0xff
        val data = dataOf(apdu)

        if (ins == 0xC0) return nextPart() // GET RESPONSE: the rest of an answer that did not fit
        if (cla and 0x10 != 0) {
            // Command chaining: more of this command follows.
            chained.write(data)
            return status(0x9000)
        }
        chained.write(data)
        val whole = chained.toByteArray()
        chained.reset()

        return when {
            ins == 0xA4 && p1 == 0x04 ->
                if (whole.contentEquals(FIDO_APPLET)) {
                    selected = true
                    answer("U2F_V2".toByteArray())
                } else {
                    status(0x6A82)
                }
            !selected -> status(0x6985)
            cla == 0x80 && ins == 0x10 -> {
                val signaturesBefore = signatures
                val credentialsBefore = credentials.size
                val reply = ctap(whole)
                // P1 0x80 says the client can be told to wait and ask again.
                if (keepAlives > 0 && p1 == 0x80 && whole[0].toInt() in setOf(MAKE_CREDENTIAL, GET_ASSERTION) && reply[0] == 0.toByte()) {
                    heldBack = reply
                    // What a request that is called off must leave untouched.
                    undo = {
                        signatures = signaturesBefore
                        while (credentials.size > credentialsBefore) credentials.removeAt(credentials.size - 1)
                    }
                    keepAlivesLeft = keepAlives
                    byteArrayOf(WAITING_FOR_TOUCH) + status(0x9100)
                } else {
                    answer(reply)
                }
            }
            cla == 0x80 && ins == 0x11 -> {
                val held = heldBack ?: return status(0x6985)
                when {
                    p1 == 0x11 -> {
                        heldBack = null
                        undo()
                        answer(byteArrayOf(ERR_KEEPALIVE_CANCEL.toByte()))
                    }
                    --keepAlivesLeft > 0 -> byteArrayOf(WAITING_FOR_TOUCH) + status(0x9100)
                    else -> {
                        heldBack = null
                        answer(held)
                    }
                }
            }
            else -> status(0x6D00)
        }
    }

    private fun dataOf(apdu: ByteArray): ByteArray {
        if (apdu.size <= 5) return ByteArray(0)
        val short = apdu[4].toInt() and 0xff
        if (short != 0) return apdu.copyOfRange(5, 5 + short)
        val length = ((apdu[5].toInt() and 0xff) shl 8) or (apdu[6].toInt() and 0xff)
        return apdu.copyOfRange(7, 7 + length)
    }

    private fun status(word: Int): ByteArray = byteArrayOf((word shr 8).toByte(), word.toByte())

    private fun answer(data: ByteArray): ByteArray {
        remaining = data
        return nextPart()
    }

    private fun nextPart(): ByteArray {
        val part = remaining.copyOfRange(0, minOf(remaining.size, 256))
        remaining = remaining.copyOfRange(part.size, remaining.size)
        return part + if (remaining.isEmpty()) status(0x9000) else status(0x6100 or minOf(remaining.size, 255))
    }

    // ----- CTAP2 -----

    private class Refusal(val code: Int) : Exception()

    private fun ctap(message: ByteArray): ByteArray {
        val command = message[0].toInt() and 0xff
        commands.add(command)
        return try {
            @Suppress("UNCHECKED_CAST")
            val parameters = if (message.size > 1) Cbor.decode(message, 1, message.size - 1) as Map<Int, Any?> else emptyMap()
            val result = when (command) {
                GET_INFO -> info()
                CLIENT_PIN -> clientPin(parameters)
                GET_ASSERTION -> getAssertion(parameters)
                MAKE_CREDENTIAL -> makeCredential(parameters)
                else -> throw Refusal(ERR_INVALID_COMMAND)
            }
            byteArrayOf(0) + if (result.isEmpty()) ByteArray(0) else Cbor.encode(result)
        } catch (refusal: Refusal) {
            byteArrayOf(refusal.code.toByte())
        }
    }

    private fun info(): Map<Int, Any> {
        val options = LinkedHashMap<String, Any>()
        options["rk"] = true
        options["up"] = true
        options["plat"] = false
        options["clientPin"] = pin != null
        if (tokensWithPermissions) options["pinUvAuthToken"] = true
        if (alwaysUv) options["alwaysUv"] = true
        if (fingerprint) options["uv"] = true
        val versions = if (tokensWithPermissions) listOf("U2F_V2", "FIDO_2_0", "FIDO_2_1") else listOf("U2F_V2", "FIDO_2_0")
        val info = LinkedHashMap<Int, Any>()
        info[1] = versions
        if (guardsCredentials) info[2] = listOf("credProtect")
        info[3] = aaguid
        info[4] = options
        info[5] = 1200
        if (pinProtocols.isNotEmpty()) info[6] = pinProtocols
        return info
    }

    private fun clientPin(parameters: Map<Int, Any?>): Map<Int, Any> {
        val protocol = protocol(parameters[1] as Int)
        return when (parameters[2] as Int) {
            1 -> mapOf(3 to pinRetries)
            2 -> {
                val w = (agreement.public as ECPublicKey).w
                mapOf(1 to linkedMapOf(1 to 2, 3 to -25, -1 to 1, -2 to coordinate(w.affineX), -3 to coordinate(w.affineY)))
            }
            // The PIN token: plainly (5, before CTAP 2.1), or with permissions (9).
            5, 9 -> {
                val withPermissions = parameters[2] == 9
                if (withPermissions && !tokensWithPermissions) throw Refusal(ERR_INVALID_SUBCOMMAND)
                val set = pin ?: throw Refusal(ERR_PIN_NOT_SET)
                if (pinRetries == 0) throw Refusal(ERR_PIN_BLOCKED)
                if (wrongInARow >= 3) throw Refusal(ERR_PIN_AUTH_BLOCKED)
                @Suppress("UNCHECKED_CAST")
                val secret = protocol.secret(sharedPoint(parameters[3] as Map<Int, Any?>))
                val given = protocol.decrypt(secret, parameters[6] as ByteArray)
                pinRetries--
                if (!given.contentEquals(sha256(set.toByteArray()).copyOf(16))) {
                    wrongInARow++
                    throw Refusal(if (pinRetries == 0) ERR_PIN_BLOCKED else if (wrongInARow >= 3) ERR_PIN_AUTH_BLOCKED else ERR_PIN_INVALID)
                }
                pinRetries = 8
                wrongInARow = 0
                val fresh = ByteArray(32).also { random.nextBytes(it) }
                token = fresh
                tokenPermissions = if (withPermissions) parameters[9] as Int else PERMISSION_MAKE or PERMISSION_ASSERT
                tokenApplication = if (withPermissions) parameters[10] as String? else null
                mapOf(2 to protocol.encrypt(secret, fresh))
            }
            else -> throw Refusal(ERR_INVALID_SUBCOMMAND)
        }
    }

    /** Whether [proof] shows that the sender has the PIN token, with [permission], for [application]. */
    private fun verified(parameters: Map<Int, Any?>, proofAt: Int, hash: ByteArray, permission: Int, application: String): Boolean {
        val proof = parameters[proofAt] as ByteArray? ?: return false
        if (pin == null) throw Refusal(ERR_PIN_NOT_SET)
        val current = token ?: throw Refusal(ERR_PIN_AUTH_INVALID)
        val protocol = protocol(parameters[proofAt + 1] as Int)
        if (!protocol.proves(current, hash, proof)) throw Refusal(ERR_PIN_AUTH_INVALID)
        if (tokenPermissions and permission == 0) throw Refusal(ERR_PIN_AUTH_INVALID)
        if (tokenApplication != null && tokenApplication != application) throw Refusal(ERR_PIN_AUTH_INVALID)
        return true
    }

    private fun getAssertion(parameters: Map<Int, Any?>): Map<Int, Any> {
        val application = parameters[1] as String
        val hash = parameters[2] as ByteArray
        @Suppress("UNCHECKED_CAST")
        val allowed = (parameters[3] as List<Map<String, Any?>>?).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val options = (parameters[5] as Map<String, Any?>?).orEmpty()
        lastOptions = options

        var userVerified = verified(parameters, 6, hash, PERMISSION_ASSERT, application)
        if (options["uv"] == true) {
            if (!fingerprint) throw Refusal(ERR_UNSUPPORTED_OPTION)
            userVerified = true
        }
        if (alwaysUv && !userVerified) throw Refusal(ERR_PUAT_REQUIRED)

        val credential = credentials.firstOrNull { known ->
            known.application == application && allowed.any { it["type"] == "public-key" && (it["id"] as ByteArray).contentEquals(known.id) } &&
                // A guarded credential does not exist for someone who has not been verified.
                (known.protection < 3 || userVerified)
        } ?: throw Refusal(ERR_NO_CREDENTIALS)

        val present = options["up"] != false
        if (present && !touched) throw Refusal(ERR_USER_ACTION_TIMEOUT)
        val flags = (if (present) FLAG_PRESENT else 0) or (if (userVerified) FLAG_VERIFIED else 0)
        signatures++
        val authenticatorData = sha256(application.toByteArray()) + flags.toByte() + ByteBuffer.allocate(4).putInt(signatures).array()
        val signature = Signature.getInstance(if (credential.ed25519) "Ed25519" else "SHA256withECDSA").run {
            initSign(credential.pair.private)
            update(authenticatorData + hash)
            sign()
        }
        return linkedMapOf(1 to mapOf("id" to credential.id, "type" to "public-key"), 2 to authenticatorData, 3 to signature)
    }

    private fun makeCredential(parameters: Map<Int, Any?>): Map<Int, Any> {
        val hash = parameters[1] as ByteArray
        @Suppress("UNCHECKED_CAST")
        val application = (parameters[2] as Map<String, Any?>)["id"] as String
        @Suppress("UNCHECKED_CAST")
        val algorithms = (parameters[4] as List<Map<String, Any?>>).map { (it["alg"] as Number).toInt() }
        val userVerified = verified(parameters, 8, hash, PERMISSION_MAKE, application)
        // With a PIN set, nothing is made without it.
        if ((pin != null || alwaysUv) && !userVerified) throw Refusal(ERR_PUAT_REQUIRED)
        val algorithm = algorithms.firstOrNull { (it == ALG_EDDSA && makesEd25519) || it == ALG_ES256 } ?: throw Refusal(ERR_UNSUPPORTED_ALGORITHM)
        @Suppress("UNCHECKED_CAST")
        val extensions = (parameters[6] as Map<String, Any?>?).orEmpty()
        if (!touched) throw Refusal(ERR_USER_ACTION_TIMEOUT)

        val credential = addCredential(algorithm == ALG_EDDSA, application)
        if (guardsCredentials) (extensions["credProtect"] as Number?)?.let { credential.protection = it.toInt() }
        lastUser = parameters[3] as Map<String, Any?>
        val publicKey: Map<Int, Any> = if (credential.ed25519) {
            linkedMapOf(1 to 1, 3 to ALG_EDDSA, -1 to 6, -2 to credential.point)
        } else {
            linkedMapOf(1 to 2, 3 to ALG_ES256, -1 to 1, -2 to credential.point.copyOfRange(1, 33), -3 to credential.point.copyOfRange(33, 65))
        }
        val flags = FLAG_PRESENT or FLAG_ATTESTED or (if (userVerified) FLAG_VERIFIED else 0)
        val authenticatorData = sha256(application.toByteArray()) + flags.toByte() + ByteArray(4) + aaguid +
            byteArrayOf((credential.id.size shr 8).toByte(), credential.id.size.toByte()) + credential.id + Cbor.encode(publicKey)
        return linkedMapOf(1 to "none", 2 to authenticatorData, 3 to emptyMap<String, Any>())
    }

    // ----- proving the PIN -----

    /** The X coordinate of the point both sides arrive at from each other's public key. */
    private fun sharedPoint(theirs: Map<Int, Any?>): ByteArray {
        val point = ECPoint(BigInteger(1, theirs[-2] as ByteArray), BigInteger(1, theirs[-3] as ByteArray))
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, (agreement.public as ECPublicKey).params))
        return KeyAgreement.getInstance("ECDH").run {
            init(agreement.private)
            doPhase(key, true)
            generateSecret()
        }
    }

    private interface PinProtocol {
        fun secret(point: ByteArray): ByteArray

        fun encrypt(secret: ByteArray, plain: ByteArray): ByteArray

        fun decrypt(secret: ByteArray, encrypted: ByteArray): ByteArray

        fun proves(key: ByteArray, message: ByteArray, proof: ByteArray): Boolean
    }

    private fun protocol(version: Int): PinProtocol {
        if (version !in pinProtocols) throw Refusal(ERR_INVALID_PARAMETER)
        return if (version == 1) ProtocolOne else ProtocolTwo(random)
    }

    /** PIN/UV auth protocol one: SHA-256 of the point, AES-256-CBC without an IV, HMAC cut to 16 bytes. */
    private object ProtocolOne : PinProtocol {
        override fun secret(point: ByteArray) = sha256(point)

        override fun encrypt(secret: ByteArray, plain: ByteArray) = aes(Cipher.ENCRYPT_MODE, secret, ByteArray(16), plain)

        override fun decrypt(secret: ByteArray, encrypted: ByteArray) = aes(Cipher.DECRYPT_MODE, secret, ByteArray(16), encrypted)

        override fun proves(key: ByteArray, message: ByteArray, proof: ByteArray) = hmac(key, message).copyOf(16).contentEquals(proof)
    }

    /** Protocol two: HKDF for an HMAC key and an AES key, a random IV in front, the whole HMAC. */
    private class ProtocolTwo(private val random: SecureRandom) : PinProtocol {
        override fun secret(point: ByteArray) = hkdf(point, "CTAP2 HMAC key") + hkdf(point, "CTAP2 AES key")

        override fun encrypt(secret: ByteArray, plain: ByteArray): ByteArray {
            val iv = ByteArray(16).also { random.nextBytes(it) }
            return iv + aes(Cipher.ENCRYPT_MODE, secret.copyOfRange(32, 64), iv, plain)
        }

        override fun decrypt(secret: ByteArray, encrypted: ByteArray) =
            aes(Cipher.DECRYPT_MODE, secret.copyOfRange(32, 64), encrypted.copyOf(16), encrypted.copyOfRange(16, encrypted.size))

        override fun proves(key: ByteArray, message: ByteArray, proof: ByteArray) = hmac(key.copyOf(32), message).contentEquals(proof)

        /** HKDF-SHA-256 with a salt of 32 zero bytes, for 32 bytes of output. */
        private fun hkdf(input: ByteArray, info: String): ByteArray = hmac(hmac(ByteArray(32), input), info.toByteArray() + 1)
    }

    companion object {
        private val FIDO_APPLET = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x06, 0x47, 0x2F, 0x00, 0x01)

        private const val MAKE_CREDENTIAL = 0x01
        private const val GET_ASSERTION = 0x02
        private const val GET_INFO = 0x04
        private const val CLIENT_PIN = 0x06

        private const val WAITING_FOR_TOUCH: Byte = 2

        private const val FLAG_PRESENT = 0x01
        private const val FLAG_VERIFIED = 0x04
        private const val FLAG_ATTESTED = 0x40

        private const val PERMISSION_MAKE = 0x01
        private const val PERMISSION_ASSERT = 0x02

        private const val ALG_ES256 = -7
        private const val ALG_EDDSA = -8

        /* Status codes, from the CTAP specification. */
        private const val ERR_INVALID_COMMAND = 0x01
        private const val ERR_INVALID_PARAMETER = 0x02
        private const val ERR_UNSUPPORTED_ALGORITHM = 0x26
        private const val ERR_UNSUPPORTED_OPTION = 0x2B
        private const val ERR_KEEPALIVE_CANCEL = 0x2D
        private const val ERR_NO_CREDENTIALS = 0x2E
        private const val ERR_USER_ACTION_TIMEOUT = 0x2F
        private const val ERR_PIN_INVALID = 0x31
        private const val ERR_PIN_BLOCKED = 0x32
        private const val ERR_PIN_AUTH_INVALID = 0x33
        private const val ERR_PIN_AUTH_BLOCKED = 0x34
        private const val ERR_PIN_NOT_SET = 0x35
        private const val ERR_PUAT_REQUIRED = 0x36
        private const val ERR_INVALID_SUBCOMMAND = 0x3E

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmac(key: ByteArray, message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

        private fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

        /** A coordinate of a P-256 point as exactly 32 bytes. */
        private fun coordinate(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else ByteArray(32 - bytes.size) + bytes
        }
    }
}
