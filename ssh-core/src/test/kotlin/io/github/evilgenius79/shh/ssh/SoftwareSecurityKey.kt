package io.github.evilgenius79.shh.ssh

import net.schmizz.sshj.common.SecurityUtils
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * A security key made of software, for tests: it keeps a private key and signs the way a FIDO2
 * authenticator does (`SHA256(application) || flags || counter || challenge`, as PROTOCOL.u2f in
 * the OpenSSH sources describes it).
 *
 * @param flags what an sk key file records about the key: 1 for "touch required", 4 for "verify required".
 */
class SoftwareSecurityKey(val ecdsa: Boolean, val application: String = "ssh:", val flags: Int = 1) {
    private val pair: KeyPair = run {
        SshSecurity.install()
        if (ecdsa) {
            SecurityUtils.getKeyPairGenerator("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
        } else {
            SecurityUtils.getKeyPairGenerator("Ed25519").generateKeyPair()
        }
    }
    val keyHandle: ByteArray = ByteArray(64).also { SecureRandom().nextBytes(it) }
    val keyType: String = if (ecdsa) "sk-ecdsa-sha2-nistp256@openssh.com" else "sk-ssh-ed25519@openssh.com"

    /** How many signatures were made. A real key counts them too. */
    var counter = 0L
        private set

    val requests = ArrayList<SecurityKeyRequest>()

    /** The public key as hardware reports it: 32 bytes for Ed25519, the uncompressed point for ECDSA. */
    val point: ByteArray = if (ecdsa) {
        val w = (pair.public as java.security.interfaces.ECPublicKey).w
        fun coordinate(value: java.math.BigInteger) = value.toByteArray().let { bytes ->
            if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else ByteArray(32 - bytes.size) + bytes
        }
        byteArrayOf(4) + coordinate(w.affineX) + coordinate(w.affineY)
    } else {
        pair.public.encoded.takeLast(32).toByteArray()
    }

    /** The key as the app holds it: the public key and the handle, without any private key. */
    val key: LoadedKey = SshKeys.securityKey(!ecdsa, point, application, keyHandle, touchRequired = flags and 1 != 0, verificationRequired = flags and 4 != 0)

    /** Signs like the hardware would, reporting [touched] and [verified] the way it would. */
    fun sign(request: SecurityKeyRequest, touched: Boolean = true, verified: Boolean = false): SecurityKeyAssertion {
        requests.add(request)
        check(request.keyHandle.contentEquals(keyHandle)) { "This is not the key the handle belongs to" }
        check(request.application == application)
        counter++
        val reported = (if (touched) SecurityKeyAssertion.USER_PRESENT else 0) or (if (verified) SecurityKeyAssertion.USER_VERIFIED else 0)
        val signed = ByteBuffer.allocate(32 + 1 + 4 + 32)
            .put(MessageDigest.getInstance("SHA-256").digest(application.toByteArray()))
            .put(reported.toByte())
            .putInt(counter.toInt())
            .put(request.challenge)
            .array()
        val signature = (if (ecdsa) SecurityUtils.getSignature("SHA256withECDSA") else SecurityUtils.getSignature("Ed25519") as Signature).run {
            initSign(pair.private)
            update(signed)
            sign()
        }
        return SecurityKeyAssertion(reported, counter, signature)
    }
}
