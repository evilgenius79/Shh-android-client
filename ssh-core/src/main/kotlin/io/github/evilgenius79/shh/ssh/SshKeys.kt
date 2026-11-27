package io.github.evilgenius79.shh.ssh

import com.hierynomus.sshj.common.KeyDecryptionFailedException
import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import net.schmizz.sshj.DefaultConfig
import com.hierynomus.sshj.userauth.fido.SecurityKeyPrivateKey
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import java.io.IOException
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The kinds of key pair the app can create. */
enum class KeyAlgorithm(val displayName: String) {
    ED25519("Ed25519"),
    ECDSA_256("ECDSA P-256"),
    ECDSA_384("ECDSA P-384"),
    ECDSA_521("ECDSA P-521"),
    RSA_3072("RSA 3072"),
    RSA_4096("RSA 4096"),
}

/** Thrown when a private key is encrypted and no passphrase was supplied. */
class PassphraseRequiredException : IOException("This key is protected by a passphrase")

/** Thrown when the supplied passphrase does not decrypt the private key. */
class WrongPassphraseException : IOException("The passphrase is not correct")

/** Thrown when text is not a private key in any supported format. */
class UnsupportedKeyException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A public key in the form the SSH protocol and `authorized_keys` use. */
class SshPublicKey(
    /** Key type name such as `ssh-ed25519` or `ecdsa-sha2-nistp256`. */
    val type: String,
    blob: ByteArray,
) {
    private val keyBlob: ByteArray = blob.copyOf()

    /** The wire encoding of the key. */
    val blob: ByteArray get() = keyBlob.copyOf()

    /** Base64 of [blob], the middle field of an `authorized_keys` line. */
    val base64: String get() = Base64.getEncoder().encodeToString(keyBlob)

    /** Fingerprint in the modern OpenSSH notation, for example `SHA256:AbCd...`. */
    val fingerprint: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

    /** Legacy colon separated MD5 fingerprint, still shown by old servers and tools. */
    val md5Fingerprint: String
        get() {
            val digest = MessageDigest.getInstance("MD5").digest(keyBlob)
            return digest.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
        }

    /** One `authorized_keys` line: type, key and an optional comment. */
    fun toAuthorizedKey(comment: String = ""): String =
        if (comment.isBlank()) "$type $base64" else "$type $base64 ${comment.trim()}"

    override fun equals(other: Any?): Boolean =
        other is SshPublicKey && type == other.type && keyBlob.contentEquals(other.keyBlob)

    override fun hashCode(): Int = 31 * type.hashCode() + keyBlob.contentHashCode()

    override fun toString(): String = "$type $fingerprint"

    companion object {
        fun of(key: PublicKey): SshPublicKey {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            return SshPublicKey(KeyType.fromKey(key).toString(), blob)
        }

        /** Parses an `authorized_keys` style line. Returns null when the text is not one. */
        fun parse(line: String): SshPublicKey? {
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 2) return null
            val blob = try {
                Base64.getDecoder().decode(parts[1])
            } catch (_: IllegalArgumentException) {
                return null
            }
            // The key type is repeated as the first string inside the blob; they must agree.
            val embedded = try {
                Buffer.PlainBuffer(blob).readString()
            } catch (_: Buffer.BufferException) {
                return null
            }
            if (embedded != parts[0]) return null
            return SshPublicKey(parts[0], blob)
        }
    }
}

/** A private key that has been read into memory, together with its public half. */
class LoadedKey internal constructor(
    val keyPair: KeyPair,
    val publicKey: SshPublicKey,
    /** A certificate for this key, offered to servers before the bare key. */
    val certificate: SshCertificate? = null,
) {
    /**
     * This key together with [certificate].
     *
     * @throws IllegalArgumentException when the certificate was issued for another key.
     */
    fun withCertificate(certificate: SshCertificate): LoadedKey {
        require(certificate.publicKey == publicKey) { "The certificate belongs to a different key" }
        return LoadedKey(keyPair, publicKey, certificate)
    }

    /** Set when the private key lives on a security key (FIDO2) and this is only the handle to it. */
    val securityKey: SecurityKeyInfo? get() = SecurityKeys.info(keyPair)

    /** Human readable description such as "Ed25519" or "RSA 4096". */
    val description: String
        get() {
            val public = keyPair.public
            return when (publicKey.type) {
                "sk-ssh-ed25519@openssh.com" -> "Ed25519 on a security key"
                "sk-ecdsa-sha2-nistp256@openssh.com" -> "ECDSA P-256 on a security key"
                "ssh-ed25519" -> "Ed25519"
                "ssh-rsa" -> "RSA " + (public as java.security.interfaces.RSAPublicKey).modulus.bitLength()
                "ssh-dss" -> "DSA"
                "ecdsa-sha2-nistp256" -> "ECDSA P-256"
                "ecdsa-sha2-nistp384" -> "ECDSA P-384"
                "ecdsa-sha2-nistp521" -> "ECDSA P-521"
                else -> publicKey.type
            }
        }
}

/** Creating, reading and writing SSH key pairs. */
object SshKeys {
    private val random = SecureRandom()

    /** Generates a new key pair. RSA generation can take several seconds on a phone. */
    fun generate(algorithm: KeyAlgorithm): LoadedKey {
        SshSecurity.install()
        val pair = when (algorithm) {
            KeyAlgorithm.ED25519 -> SecurityUtils.getKeyPairGenerator("Ed25519").generateKeyPair()
            KeyAlgorithm.ECDSA_256 -> generateEc("secp256r1")
            KeyAlgorithm.ECDSA_384 -> generateEc("secp384r1")
            KeyAlgorithm.ECDSA_521 -> generateEc("secp521r1")
            KeyAlgorithm.RSA_3072 -> generateRsa(3072)
            KeyAlgorithm.RSA_4096 -> generateRsa(4096)
        }
        return LoadedKey(pair, SshPublicKey.of(pair.public))
    }

    private fun generateEc(curve: String): KeyPair {
        val generator = SecurityUtils.getKeyPairGenerator("EC")
        generator.initialize(ECGenParameterSpec(curve), random)
        return generator.generateKeyPair()
    }

    private fun generateRsa(bits: Int): KeyPair {
        val generator = SecurityUtils.getKeyPairGenerator("RSA")
        generator.initialize(bits, random)
        return generator.generateKeyPair()
    }

    /**
     * Reads a private key in any of the formats OpenSSH and PuTTY produce: the OpenSSH format
     * (`BEGIN OPENSSH PRIVATE KEY`), PEM (`BEGIN RSA/EC/DSA PRIVATE KEY`), PKCS#8 and PuTTY's
     * `.ppk`.
     *
     * @throws PassphraseRequiredException the key is encrypted and [passphrase] is null.
     * @throws WrongPassphraseException [passphrase] does not decrypt the key.
     * @throws UnsupportedKeyException the text is not a usable private key.
     */
    fun load(privateKeyText: String, passphrase: CharArray? = null): LoadedKey {
        SshSecurity.install()
        val text = privateKeyText.trim() + "\n"
        val format = try {
            KeyProviderUtil.detectKeyFileFormat(text, false)
        } catch (e: IOException) {
            throw UnsupportedKeyException("Not a private key", e)
        }
        if (format == KeyFormat.Unknown) throw UnsupportedKeyException("Not a private key in a supported format")
        val provider: FileKeyProvider = Factory.Named.Util.create(KEY_FILE_CONFIG.fileKeyProviderFactories, format.toString())
            ?: throw UnsupportedKeyException("No reader available for $format keys")

        var asked = false
        val finder = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray {
                asked = true
                // sshj wipes the array it is given, so hand out a copy.
                return passphrase?.copyOf() ?: CharArray(0)
            }

            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }
        try {
            provider.init(text, null, finder)
            val private = provider.private
            val public = provider.public
            return LoadedKey(KeyPair(public, private), SshPublicKey.of(public))
        } catch (e: Exception) {
            if (asked && passphrase == null) throw PassphraseRequiredException()
            if (asked || e is KeyDecryptionFailedException) throw WrongPassphraseException()
            throw UnsupportedKeyException(e.message ?: "The private key could not be read", e)
        }
    }

    /** True when [privateKeyText] is a private key that needs a passphrase to be read. */
    fun isEncrypted(privateKeyText: String): Boolean = try {
        load(privateKeyText, null)
        false
    } catch (_: PassphraseRequiredException) {
        true
    }

    /**
     * Writes a key pair in the OpenSSH private key format understood by `ssh`, `ssh-add` and
     * `ssh-keygen`. With a [passphrase] the key is encrypted the same way `ssh-keygen` does it
     * (bcrypt key derivation, AES-256-CTR).
     */
    fun toOpenSshPrivateKey(key: LoadedKey, comment: String = "", passphrase: CharArray? = null): String {
        SshSecurity.install()
        val publicBlob = key.publicKey.blob
        val encrypted = passphrase != null && passphrase.isNotEmpty()
        val blockSize = if (encrypted) 16 else 8

        val secret = Buffer.PlainBuffer()
        val check = random.nextInt()
        secret.putUInt32(check.toLong() and 0xFFFFFFFFL)
        secret.putUInt32(check.toLong() and 0xFFFFFFFFL)
        secret.putString(key.publicKey.type)
        writePrivateFields(secret, key)
        secret.putString(comment, StandardCharsets.UTF_8)
        var pad = 1
        while (secret.available() % blockSize != 0) secret.putByte(pad++.toByte())
        var secretBytes = secret.compactData

        val out = Buffer.PlainBuffer()
        out.putRawBytes(OPENSSH_MAGIC)
        if (encrypted) {
            val salt = ByteArray(16).also { random.nextBytes(it) }
            val material = ByteArray(32 + 16)
            val passwordBytes = String(passphrase).toByteArray(StandardCharsets.UTF_8)
            BCrypt().pbkdf(passwordBytes, salt, BCRYPT_ROUNDS, material)
            java.util.Arrays.fill(passwordBytes, 0)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(material, 0, 32, "AES"),
                IvParameterSpec(material, 32, 16),
            )
            secretBytes = cipher.doFinal(secretBytes)
            java.util.Arrays.fill(material, 0)
            out.putString("aes256-ctr")
            out.putString("bcrypt")
            out.putBytes(Buffer.PlainBuffer().putBytes(salt).putUInt32(BCRYPT_ROUNDS.toLong()).compactData)
        } else {
            out.putString("none")
            out.putString("none")
            out.putString("")
        }
        out.putUInt32(1)
        out.putBytes(publicBlob)
        out.putBytes(secretBytes)

        val body = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(out.compactData)
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    private fun writePrivateFields(out: Buffer.PlainBuffer, key: LoadedKey) {
        val private = key.keyPair.private
        val blob = Buffer.PlainBuffer(key.publicKey.blob)
        blob.readString() // key type
        when (key.publicKey.type) {
            "ssh-ed25519" -> {
                val publicBytes = blob.readBytes()
                val seed = ASN1OctetString.getInstance(PrivateKeyInfo.getInstance(private.encoded).parsePrivateKey()).octets
                require(seed.size == 32 && publicBytes.size == 32) { "Unexpected Ed25519 key encoding" }
                out.putBytes(publicBytes)
                out.putBytes(seed + publicBytes)
                java.util.Arrays.fill(seed, 0)
            }
            "ssh-rsa" -> {
                val rsa = private as? RSAPrivateCrtKey ?: throw UnsupportedKeyException("RSA key lacks its CRT parameters")
                out.putMPInt(rsa.modulus)
                out.putMPInt(rsa.publicExponent)
                out.putMPInt(rsa.privateExponent)
                out.putMPInt(rsa.crtCoefficient)
                out.putMPInt(rsa.primeP)
                out.putMPInt(rsa.primeQ)
            }
            "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521" -> {
                val curveName = blob.readString()
                val point = blob.readBytes()
                val d: BigInteger = (private as ECPrivateKey).s
                out.putString(curveName)
                out.putBytes(point)
                out.putMPInt(d)
            }
            "sk-ssh-ed25519@openssh.com" -> {
                out.putBytes(blob.readBytes())
                writeSecurityKeyFields(out, blob.readString(), private)
            }
            "sk-ecdsa-sha2-nistp256@openssh.com" -> {
                out.putString(blob.readString()) // the name of the curve
                out.putBytes(blob.readBytes()) // the point
                writeSecurityKeyFields(out, blob.readString(), private)
            }
            else -> throw UnsupportedKeyException("Cannot write ${key.publicKey.type} keys")
        }
    }

    /** What an sk key file holds in place of a private key: see "sshkey_private_serialize" in OpenSSH. */
    private fun writeSecurityKeyFields(out: Buffer.PlainBuffer, application: String, private: java.security.PrivateKey) {
        val handle = private as? SecurityKeyPrivateKey ?: throw UnsupportedKeyException("The key lacks the handle of its security key")
        out.putString(application)
        out.putByte(handle.flags)
        out.putBytes(handle.keyHandle)
        out.putBytes(ByteArray(0)) // reserved
    }

    private val OPENSSH_MAGIC = "openssh-key-v1\u0000".toByteArray(StandardCharsets.US_ASCII)

    /** Same work factor `ssh-keygen` uses by default. */
    private const val BCRYPT_ROUNDS = 16

    /** Only used for its list of key file readers. */
    private val KEY_FILE_CONFIG by lazy { DefaultConfig() }
}
