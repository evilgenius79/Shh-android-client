package io.github.evilgenius79.shh.ssh

import com.hierynomus.sshj.userauth.fido.SecurityKeyPrivateKey
import com.hierynomus.sshj.userauth.fido.SecurityKeySignatureData
import com.hierynomus.sshj.userauth.fido.SecurityKeySigner
import java.security.KeyPair

/*
 * Keys that live on a security key (a FIDO2 authenticator): OpenSSH's "sk" key types. The
 * private key never leaves the hardware. What an "id_ed25519_sk" file holds is only a handle the
 * hardware recognises its key by, so logging in means asking the hardware for a signature.
 * See PROTOCOL.u2f in the OpenSSH sources.
 */

/** What a security key is asked to sign. */
class SecurityKeyRequest(
    /** `sk-ssh-ed25519@openssh.com` or `sk-ecdsa-sha2-nistp256@openssh.com`. */
    val keyType: String,
    /** What the key was made for, normally `ssh:`. To the hardware this is the relying party. */
    val application: String,
    keyHandle: ByteArray,
    challenge: ByteArray,
    /** The key was made to be touched for every signature. */
    val touchRequired: Boolean,
    /** The key was made to need its PIN (or a fingerprint) for every signature. */
    val verificationRequired: Boolean,
) {
    private val handle = keyHandle.copyOf()
    private val hash = challenge.copyOf()

    /** Names the key on the hardware. To the hardware this is the credential id. */
    val keyHandle: ByteArray get() = handle.copyOf()

    /** SHA-256 of what SSH wants signed. To the hardware this is the client data hash. */
    val challenge: ByteArray get() = hash.copyOf()
}

/** What the security key answered. */
class SecurityKeyAssertion(
    /** The flags of the authenticator data: bit 0 says the key was touched, bit 2 that the user was verified. */
    val flags: Int,
    /** The signature counter of the authenticator data. */
    val counter: Long,
    signature: ByteArray,
) {
    private val bytes = signature.copyOf()

    /** The signature as the hardware gives it: 64 bytes for Ed25519, ASN.1 DER for ECDSA. */
    val signature: ByteArray get() = bytes.copyOf()

    companion object {
        /** The flag that says someone touched the key. */
        const val USER_PRESENT = 0x01

        /** The flag that says the key checked a PIN or a fingerprint. */
        const val USER_VERIFIED = 0x04
    }
}

/** What became of the request to sign with a security key. */
sealed interface SecurityKeyAnswer {
    class Signed(val assertion: SecurityKeyAssertion) : SecurityKeyAnswer

    /** This key is not at hand: go on with the other ways of logging in. */
    data object Skip : SecurityKeyAnswer

    /** Give up logging in. */
    data object Cancel : SecurityKeyAnswer
}

/** What is known about a key that lives on a security key. */
class SecurityKeyInfo(val application: String, val touchRequired: Boolean, val verificationRequired: Boolean)

internal object SecurityKeys {
    /* Flags of an sk key, from OpenSSH's sk-api.h. */
    const val USER_PRESENCE_REQUIRED = 0x01
    const val USER_VERIFICATION_REQUIRED = 0x04

    fun info(pair: KeyPair): SecurityKeyInfo? {
        val private = pair.private as? SecurityKeyPrivateKey ?: return null
        val flags = private.flags.toInt()
        return SecurityKeyInfo(private.application, flags and USER_PRESENCE_REQUIRED != 0, flags and USER_VERIFICATION_REQUIRED != 0)
    }

    /**
     * [pair], which must be a security key, with [sign] as the way to reach the hardware.
     *
     * [sign] is called while the server waits for the signature. Whatever it answers, something
     * has to be sent: the SSH library offers no way of taking a key back once the server has
     * said it would accept it. So when there is no signature, one that cannot be right is sent.
     * The server refuses it like a wrong password and the login goes on from there.
     */
    fun withSigner(pair: KeyPair, sign: (SecurityKeyRequest) -> SecurityKeyAssertion?): KeyPair {
        val private = pair.private as SecurityKeyPrivateKey
        val flags = private.flags.toInt()
        val signer = SecurityKeySigner { request ->
            val asked = SecurityKeyRequest(
                keyType = request.keyTypeName,
                application = request.application,
                keyHandle = request.keyHandle,
                challenge = request.challenge,
                touchRequired = flags and USER_PRESENCE_REQUIRED != 0,
                verificationRequired = flags and USER_VERIFICATION_REQUIRED != 0,
            )
            val assertion = try {
                sign(asked)
            } catch (e: RuntimeException) {
                // This runs where the library reads from the server. An exception here would
                // take the whole connection down instead of failing this one way of logging in.
                null
            }
            if (assertion != null) {
                SecurityKeySignatureData(assertion.flags.toByte(), assertion.counter, assertion.signature)
            } else {
                SecurityKeySignatureData(0, 0, noSignature(request.keyTypeName))
            }
        }
        return KeyPair(pair.public, SecurityKeyPrivateKey(private.keyTypeName, private.publicKey, private.flags, private.keyHandle, signer))
    }

    /** Something with the shape of a signature for [keyType] that no key made. */
    private fun noSignature(keyType: String): ByteArray =
        if (keyType.startsWith("sk-ecdsa")) {
            // ASN.1 DER for the pair of integers (1, 1).
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01)
        } else {
            ByteArray(64)
        }
}
