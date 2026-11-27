package io.github.evilgenius79.shh.ssh

import com.hierynomus.sshj.userauth.certificate.Certificate
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64
import java.util.Date

/**
 * An OpenSSH certificate: a public key together with a certificate authority's statement of who
 * may use it and until when. Servers that trust the authority accept the key without having it
 * in any `authorized_keys` file.
 */
class SshCertificate private constructor(
    /** The certificate's algorithm name, e.g. `ssh-ed25519-cert-v01@openssh.com`. */
    val type: String,
    blob: ByteArray,
    internal val key: Certificate<PublicKey>,
) {
    private val certificateBlob: ByteArray = blob.copyOf()

    /** The key the certificate is for. */
    val publicKey: SshPublicKey = SshPublicKey.of(key.key)

    /** The name the authority gave this certificate. */
    val keyId: String get() = key.id.orEmpty()

    /** The accounts it may log in to. Empty means any account. */
    val principals: List<String> get() = key.validPrincipals.orEmpty()

    val validAfter: Date get() = key.validAfter

    val validBefore: Date get() = key.validBefore

    /** True for a certificate that identifies a server rather than a user. */
    val isHostCertificate: Boolean get() = key.type == 2L

    fun isValidAt(time: Date = Date()): Boolean = !time.before(validAfter) && time.before(validBefore)

    /** The line as it stands in a `-cert.pub` file. */
    fun toLine(comment: String = ""): String =
        (type + " " + Base64.getEncoder().encodeToString(certificateBlob) + " " + comment).trim()

    internal val blob: ByteArray get() = certificateBlob.copyOf()

    companion object {
        /**
         * Reads the line of a `-cert.pub` file.
         *
         * @throws UnsupportedKeyException when the text is not an OpenSSH certificate.
         */
        @Throws(UnsupportedKeyException::class)
        fun parse(line: String): SshCertificate {
            SshSecurity.install()
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 2 || !parts[0].contains("-cert-")) throw UnsupportedKeyException("This is not an SSH certificate")
            val blob = try {
                Base64.getDecoder().decode(parts[1])
            } catch (e: IllegalArgumentException) {
                throw UnsupportedKeyException("The certificate is damaged", e)
            }
            val parsed = try {
                Buffer.PlainBuffer(blob).readPublicKey()
            } catch (e: Exception) {
                throw UnsupportedKeyException("The certificate could not be read: ${e.message}", e)
            }
            if (parsed !is Certificate<*> || KeyType.fromKey(parsed).toString() != parts[0]) {
                throw UnsupportedKeyException("This is not an SSH certificate")
            }
            @Suppress("UNCHECKED_CAST")
            return SshCertificate(parts[0], blob, parsed as Certificate<PublicKey>)
        }
    }
}

/** Offers a certificate for a login and signs with the private key it belongs to. */
internal class CertificateKeyProvider(private val key: LoadedKey, private val certificate: SshCertificate) : KeyProvider {
    override fun getPrivate(): PrivateKey = key.keyPair.private

    override fun getPublic(): PublicKey = certificate.key

    override fun getType(): KeyType = KeyType.fromKey(certificate.key)
}
