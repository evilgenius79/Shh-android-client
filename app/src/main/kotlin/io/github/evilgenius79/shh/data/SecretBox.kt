package io.github.evilgenius79.shh.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the secrets (passwords, private keys) that are written to the app's data file. */
interface SecretBox {
    /** Returns text that can be stored and later handed to [decrypt]. */
    fun encrypt(plain: ByteArray): String

    /** @throws GeneralSecurityException when [stored] was not produced by this box. */
    @Throws(GeneralSecurityException::class)
    fun decrypt(stored: String): ByteArray

    fun encryptText(plain: String): String = encrypt(plain.toByteArray(Charsets.UTF_8))

    @Throws(GeneralSecurityException::class)
    fun decryptText(stored: String): String = String(decrypt(stored), Charsets.UTF_8)
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore and cannot be extracted from the
 * device. Copying the app's data file elsewhere therefore does not expose any secret.
 *
 * Stored format: base64 of a 12 byte nonce followed by the ciphertext and authentication tag.
 */
class KeystoreSecretBox(private val alias: String = "shh-secrets-v1") : SecretBox {
    private val key: SecretKey by lazy { loadOrCreateKey() }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): String {
        // The provider is not named on purpose: the Keystore picks the cipher that fits its key.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv
        val sealed = cipher.doFinal(plain)
        return Base64.encodeToString(nonce + sealed, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): ByteArray {
        val bytes = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("Stored secret is not valid base64", e)
        }
        if (bytes.size <= NONCE_SIZE) throw GeneralSecurityException("Stored secret is too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_SIZE))
        return cipher.doFinal(bytes, NONCE_SIZE, bytes.size - NONCE_SIZE)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_SIZE = 12
        const val TAG_BITS = 128
    }
}
