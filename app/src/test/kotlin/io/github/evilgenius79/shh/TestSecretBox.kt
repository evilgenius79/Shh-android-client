package io.github.evilgenius79.shh

import io.github.evilgenius79.shh.data.SecretBox
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * A stand-in for the Android Keystore in JVM tests. It is NOT secure: it only marks values with
 * a prefix that is different per instance, which is enough to tell "encrypted for this device"
 * apart from "encrypted for another device" and from plain text.
 */
class TestSecretBox(private val device: String = "device-a") : SecretBox {
    override fun encrypt(plain: ByteArray): String = "$device:" + Base64.getEncoder().encodeToString(plain)

    override fun decrypt(stored: String): ByteArray {
        if (!stored.startsWith("$device:")) throw GeneralSecurityException("Not encrypted for this device")
        return Base64.getDecoder().decode(stored.substring(device.length + 1))
    }
}
