package io.github.evilgenius79.shh.data

import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when a backup cannot be read: wrong passphrase, damaged file or not a backup at all. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns the app's data into a file that can be restored on another device.
 *
 * Secrets in [AppData] are encrypted with a key that never leaves the device they were created
 * on, so a plain copy of the data would be useless elsewhere. A backup therefore decrypts them
 * and encrypts the complete document again with a key derived from a passphrase the user
 * chooses (PBKDF2-HMAC-SHA256, AES-256-GCM). Restoring does the reverse.
 *
 * File format: the line `SHH-BACKUP-1`, then base64 of salt (16 bytes), nonce (12 bytes) and
 * ciphertext.
 */
object Backup {
    private const val HEADER = "SHH-BACKUP-1"
    private const val ITERATIONS = 210_000
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val random = SecureRandom()

    fun export(data: AppData, secrets: SecretBox, passphrase: CharArray): String {
        require(passphrase.isNotEmpty()) { "A backup needs a passphrase" }
        // Re-encode every secret from "device key" to plain text inside the protected document.
        val portable = data.copy(
            hosts = data.hosts.map { host -> host.copy(encryptedPassword = host.encryptedPassword?.let { secrets.decryptText(it) }) },
            identities = data.identities.map { it.copy(encryptedPrivateKey = secrets.decryptText(it.encryptedPrivateKey)) },
        )
        val plain = json.encodeToString(AppData.serializer(), portable).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(HEADER.toByteArray(Charsets.US_ASCII))
        val sealed = cipher.doFinal(plain)
        val body = Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(salt + nonce + sealed)
        return "$HEADER\n$body\n"
    }

    /**
     * Reads a backup and re-encrypts its secrets for this device.
     *
     * @throws BackupException when the text is not a backup or the passphrase is wrong.
     */
    @Throws(BackupException::class)
    fun import(text: String, secrets: SecretBox, passphrase: CharArray): AppData {
        val trimmed = text.trim()
        if (!trimmed.startsWith(HEADER)) throw BackupException("This is not a Shh backup file")
        val bytes = try {
            Base64.getMimeDecoder().decode(trimmed.substring(HEADER.length).trim())
        } catch (e: IllegalArgumentException) {
            throw BackupException("The backup file is damaged", e)
        }
        if (bytes.size <= SALT_SIZE + NONCE_SIZE) throw BackupException("The backup file is damaged")
        val salt = bytes.copyOfRange(0, SALT_SIZE)
        val nonce = bytes.copyOfRange(SALT_SIZE, SALT_SIZE + NONCE_SIZE)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
            cipher.updateAAD(HEADER.toByteArray(Charsets.US_ASCII))
            cipher.doFinal(bytes, SALT_SIZE + NONCE_SIZE, bytes.size - SALT_SIZE - NONCE_SIZE)
        } catch (e: GeneralSecurityException) {
            throw BackupException("Wrong passphrase, or the backup file is damaged", e)
        }
        val portable = try {
            json.decodeFromString(AppData.serializer(), String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("The backup could not be understood: ${e.message}", e)
        }
        return portable.copy(
            hosts = portable.hosts.map { host -> host.copy(encryptedPassword = host.encryptedPassword?.let { secrets.encryptText(it) }) },
            identities = portable.identities.map { it.copy(encryptedPrivateKey = secrets.encryptText(it.encryptedPrivateKey)) },
        )
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
