package io.github.evilgenius79.shh.security

import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocol
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2
import com.yubico.yubikit.fido.webauthn.AuthenticatorData
import io.github.evilgenius79.shh.ssh.LoadedKey
import io.github.evilgenius79.shh.ssh.SecurityKeyAssertion
import io.github.evilgenius79.shh.ssh.SecurityKeyRequest
import io.github.evilgenius79.shh.ssh.SshKeys
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom

/** Why a security key did not sign. The message is written for the user. */
sealed class SecurityKeyException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The key wants its PIN first. [wrong] when a PIN was given and the key refused it;
     * [attemptsLeft] is how many more tries the key allows, where it says.
     */
    class PinNeeded(val wrong: Boolean, val attemptsLeft: Int?) :
        SecurityKeyException(
            if (!wrong) "This key needs its PIN." else "That PIN is not right." + (attemptsLeft?.let { " $it more ${if (it == 1) "try" else "tries"} before the key locks itself." } ?: ""),
        )

    /** Nothing came of it. [retry] says whether presenting the key again may help. */
    class Failed(message: String, val retry: Boolean, cause: Throwable? = null) : SecurityKeyException(message, cause)
}

/** Something to do with a security key once it is there and its FIDO2 function is open. */
fun interface SecurityKeyTask<T> {
    /**
     * @param pin the key's PIN if the user has given it. Without one, a task that needs it
     * throws [SecurityKeyException.PinNeeded].
     * @param state tells about the key waiting to be touched, and can call the request off.
     */
    @Throws(SecurityKeyException::class, IOException::class, CommandException::class)
    fun run(session: Ctap2Session, pin: CharArray?, state: CommandState): T
}

/**
 * Has a security key sign for an SSH login, or make a new key, speaking CTAP2 (the protocol of FIDO2 authenticators)
 * through the Yubico SDK. This is what `ssh` does through libfido2, see sk-usbhid.c in OpenSSH.
 */
object SecurityKeyClient {
    /**
     * Asks the key behind [session] for the signature [request] describes.
     *
     * @param pin the key's PIN if the user has given it. Without one the key is asked to sign
     * without, and [SecurityKeyException.PinNeeded] says that it will not.
     * @param state tells about the key waiting to be touched, and can call the request off.
     */
    @Throws(SecurityKeyException::class, IOException::class, CommandException::class)
    internal fun sign(session: Ctap2Session, request: SecurityKeyRequest, pin: CharArray?, state: CommandState? = null): SecurityKeyAssertion {
        val asked = HashMap<String, Any>()
        // A key made with "no-touch-required" signs without being touched. Otherwise the
        // key's own default applies, which is to wait for the touch.
        if (!request.touchRequired) asked["up"] = false
        val proof = proofOfPin(session, pin, request.challenge, ClientPin.PIN_PERMISSION_GA, request.application)
        if (proof == null && (request.verificationRequired || session.cachedInfo.options["alwaysUv"] == true)) {
            verifyWithoutPin(session, asked, "This SSH key was made to need a PIN, but the security key has none set. Set one with the tool of its maker.")
        }
        val credential = mapOf("type" to "public-key", "id" to request.keyHandle)
        val assertions = session.getAssertions(
            request.application,
            request.challenge,
            listOf(credential),
            null,
            asked.ifEmpty { null },
            proof?.first,
            proof?.second,
            state,
        )
        val first = assertions.firstOrNull() ?: throw SecurityKeyException.Failed("The security key answered with nothing.", retry = true)
        return assertionOf(first.authenticatorData, first.signature)
    }

    /**
     * Makes a new key on the security key, the way `ssh-keygen -t ed25519-sk` does: a credential
     * for [application] that is not stored on the key, whose handle goes into the key file.
     *
     * @param verificationRequired have the key insist on its PIN for every signature
     * ("verify-required"). The security key is told to guard the credential accordingly where
     * it can.
     */
    @Throws(SecurityKeyException::class, IOException::class, CommandException::class)
    internal fun create(
        session: Ctap2Session,
        ed25519: Boolean,
        application: String,
        verificationRequired: Boolean,
        pin: CharArray?,
        state: CommandState? = null,
    ): LoadedKey {
        val info = session.cachedInfo
        // Nothing depends on this value: it only has to be there to be signed over.
        val hash = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val proof = proofOfPin(session, pin, hash, ClientPin.PIN_PERMISSION_MC, application)
        val asked = HashMap<String, Any>()
        // A key with a PIN makes nothing without it. Asking first saves a wasted touch.
        if (proof == null && (info.options["clientPin"] == true || info.options["alwaysUv"] == true || verificationRequired)) {
            verifyWithoutPin(session, asked, "A key that needs a PIN at every login takes a security key with a PIN set. Set one with the tool of its maker.")
        }
        val extensions = if (verificationRequired && "credProtect" in info.extensions) mapOf("credProtect" to CRED_PROTECT_VERIFICATION_REQUIRED) else null
        val made = session.makeCredential(
            hash,
            mapOf("id" to application),
            // What ssh-keygen sends for a key that is not stored on the security key.
            mapOf("id" to ByteArray(32), "name" to "openssh"),
            listOf(mapOf("type" to "public-key", "alg" to if (ed25519) COSE_EDDSA else COSE_ES256)),
            null,
            extensions,
            asked.ifEmpty { null },
            proof?.first,
            proof?.second,
            null,
            state,
        )
        return keyOf(made.authenticatorData, ed25519, application, verificationRequired)
    }

    /**
     * The proof for the key that the user knows its PIN, bound to [hash], together with the
     * number of the protocol it was made with. Null when no PIN was given.
     */
    private fun proofOfPin(session: Ctap2Session, pin: CharArray?, hash: ByteArray, permission: Int, application: String): Pair<ByteArray, Int>? {
        if (pin == null) return null
        if (session.cachedInfo.options["clientPin"] != true) {
            throw SecurityKeyException.Failed("This security key has no PIN set, so it cannot check one.", retry = false)
        }
        val protocol = protocolOf(session)
        val token = ClientPin(session, protocol).getPinToken(pin, permission, application)
        try {
            return protocol.authenticate(token, hash) to protocol.version
        } finally {
            token.fill(0)
        }
    }

    /** Has the key verify its user without a PIN from here: by fingerprint, or not at all. */
    private fun verifyWithoutPin(session: Ctap2Session, asked: MutableMap<String, Any>, withoutPin: String) {
        val options = session.cachedInfo.options
        when {
            // A fingerprint reader on the key itself, with a finger enrolled.
            options["uv"] == true -> asked["uv"] = true
            options["clientPin"] == true -> throw SecurityKeyException.PinNeeded(wrong = false, attemptsLeft = null)
            else -> throw SecurityKeyException.Failed(withoutPin, retry = false)
        }
    }

    /**
     * The new key, from the authenticator data the security key answers with: after the fields
     * every such answer has come the key's handle and its public key.
     */
    internal fun keyOf(authenticatorData: ByteArray, ed25519: Boolean, application: String, verificationRequired: Boolean): LoadedKey {
        val made = try {
            AuthenticatorData.parseFrom(ByteBuffer.wrap(authenticatorData)).attestedCredentialData
        } catch (e: RuntimeException) {
            null
        } ?: throw SecurityKeyException.Failed("The security key did not say what key it made.", retry = true)
        val public = made.cosePublicKey
        val point = if (ed25519) {
            public[COSE_X] as? ByteArray
        } else {
            val x = public[COSE_X] as? ByteArray
            val y = public[COSE_Y] as? ByteArray
            if (x?.size == 32 && y?.size == 32) byteArrayOf(4) + x + y else null
        }
        val wanted = if (ed25519) COSE_EDDSA else COSE_ES256
        if (point == null || (public[COSE_ALGORITHM] as? Number)?.toInt() != wanted) {
            throw SecurityKeyException.Failed("The security key made a key of another kind than was asked for.", retry = false)
        }
        return try {
            SshKeys.securityKey(ed25519, point, application, made.credentialId, touchRequired = true, verificationRequired = verificationRequired)
        } catch (e: Exception) {
            throw SecurityKeyException.Failed("The security key made a key that cannot be used: ${e.message}", retry = false, cause = e)
        }
    }

    /**
     * The whole business with one key that has turned up: opens its FIDO2 function with
     * [connect], runs [task] on it, keeps the caller posted while the key waits to be touched,
     * and puts whatever goes wrong into words for the user.
     */
    @Throws(SecurityKeyException::class)
    fun <T> perform(
        connect: () -> Ctap2Session,
        task: SecurityKeyTask<T>,
        pin: CharArray?,
        onWaitingForTouch: () -> Unit,
        calledOff: () -> Boolean,
    ): T {
        val state = object : CommandState() {
            override fun onKeepAliveStatus(status: Byte) {
                if (status == STATUS_UPNEEDED) onWaitingForTouch()
                if (calledOff()) cancel()
            }
        }
        return open(connect) { session ->
            try {
                task.run(session, pin, state)
            } catch (e: CtapException) {
                throw explain(e, session, pinGiven = pin != null)
            } catch (e: CommandException) {
                throw SecurityKeyException.Failed("The security key gave an answer that makes no sense: ${e.message}", retry = true, cause = e)
            } catch (e: IOException) {
                throw SecurityKeyException.Failed(LINK_BROKE, retry = true, cause = e)
            }
        }
    }

    /** Having the key sign for an SSH login. */
    fun signing(request: SecurityKeyRequest) = SecurityKeyTask { session, pin, state -> sign(session, request, pin, state) }

    /** Making a new SSH key on the key. */
    fun creating(ed25519: Boolean, application: String = "ssh:", verificationRequired: Boolean = false) =
        SecurityKeyTask { session, pin, state -> create(session, ed25519, application, verificationRequired, pin, state) }

    /** Opens the FIDO2 function of a key, telling the user in plain words when it has none. */
    @Throws(SecurityKeyException::class)
    fun <T> open(connect: () -> Ctap2Session, use: (Ctap2Session) -> T): T {
        val session = try {
            connect()
        } catch (e: ApplicationNotAvailableException) {
            throw SecurityKeyException.Failed("This device does not offer FIDO2. Is it a security key?", retry = false, cause = e)
        } catch (e: CommandException) {
            throw SecurityKeyException.Failed("The device did not answer like a FIDO2 security key: ${e.message}", retry = true, cause = e)
        } catch (e: IOException) {
            throw SecurityKeyException.Failed(LINK_BROKE, retry = true, cause = e)
        }
        try {
            return use(session)
        } finally {
            try {
                session.close()
            } catch (_: IOException) {
                // The key may be gone already. There is nothing left to do with it anyway.
            }
        }
    }

    /**
     * What the key says about itself and its answer: the authenticator data begins with the
     * SHA-256 of the application (32 bytes), then the flags (1 byte) and the counter (4 bytes).
     */
    internal fun assertionOf(authenticatorData: ByteArray, signature: ByteArray): SecurityKeyAssertion {
        if (authenticatorData.size < 37) throw SecurityKeyException.Failed("The security key gave an answer that is too short.", retry = true)
        val flags = authenticatorData[32].toInt() and 0xff
        var counter = 0L
        for (index in 33..36) counter = (counter shl 8) or (authenticatorData[index].toLong() and 0xff)
        return SecurityKeyAssertion(flags, counter, signature)
    }

    /** The way of proving the PIN that the key likes best among those known here. */
    private fun protocolOf(session: Ctap2Session): PinUvAuthProtocol {
        // Keys from before the list existed speak the first protocol.
        val offered = session.cachedInfo.pinUvAuthProtocols.ifEmpty { listOf(1) }
        for (version in offered) {
            when (version) {
                2 -> return PinUvAuthProtocolV2()
                1 -> return PinUvAuthProtocolV1()
            }
        }
        throw SecurityKeyException.Failed("This security key checks its PIN in a way this app does not know.", retry = false)
    }

    private fun explain(error: CtapException, session: Ctap2Session, pinGiven: Boolean): SecurityKeyException = when (error.ctapError) {
        CtapException.ERR_PIN_INVALID -> SecurityKeyException.PinNeeded(wrong = true, attemptsLeft = attemptsLeft(session))
        CtapException.ERR_PUAT_REQUIRED ->
            if (pinGiven) SecurityKeyException.Failed("The security key did not accept the proof of its PIN.", retry = true, cause = error)
            else SecurityKeyException.PinNeeded(wrong = false, attemptsLeft = null)
        CtapException.ERR_PIN_AUTH_INVALID -> SecurityKeyException.Failed("The security key did not accept the proof of its PIN.", retry = true, cause = error)
        CtapException.ERR_PIN_BLOCKED ->
            SecurityKeyException.Failed("The PIN of this security key is locked after too many wrong tries. The key has to be reset with the tool of its maker.", retry = false, cause = error)
        CtapException.ERR_PIN_AUTH_BLOCKED ->
            SecurityKeyException.Failed("Too many wrong PINs in a row. Take the key away, or unplug it, and present it again.", retry = true, cause = error)
        CtapException.ERR_PIN_NOT_SET -> SecurityKeyException.Failed("This needs a PIN, and the security key has none set.", retry = false, cause = error)
        CtapException.ERR_NO_CREDENTIALS, CtapException.ERR_INVALID_CREDENTIAL ->
            SecurityKeyException.Failed("This is not the security key that this SSH key belongs to.", retry = true, cause = error)
        CtapException.ERR_USER_ACTION_TIMEOUT, CtapException.ERR_ACTION_TIMEOUT, CtapException.ERR_TIMEOUT ->
            SecurityKeyException.Failed("The security key was not touched in time.", retry = true, cause = error)
        CtapException.ERR_OPERATION_DENIED, CtapException.ERR_NOT_ALLOWED ->
            SecurityKeyException.Failed("The security key refused to sign.", retry = true, cause = error)
        CtapException.ERR_UV_BLOCKED, CtapException.ERR_UV_INVALID ->
            SecurityKeyException.Failed("The security key did not recognise the fingerprint.", retry = true, cause = error)
        CtapException.ERR_KEEPALIVE_CANCEL -> SecurityKeyException.Failed("Called off.", retry = true, cause = error)
        CtapException.ERR_UNSUPPORTED_OPTION, CtapException.ERR_INVALID_OPTION, CtapException.ERR_UNSUPPORTED_EXTENSION ->
            SecurityKeyException.Failed("This security key cannot do what is asked of it here.", retry = false, cause = error)
        CtapException.ERR_UNSUPPORTED_ALGORITHM ->
            SecurityKeyException.Failed("This security key cannot make keys of this kind. Older ones only know ECDSA.", retry = false, cause = error)
        CtapException.ERR_KEY_STORE_FULL -> SecurityKeyException.Failed("The security key has no room for another key.", retry = false, cause = error)
        else -> SecurityKeyException.Failed("The security key answered with an error (${error.errorName}).", retry = true, cause = error)
    }

    const val LINK_BROKE = "The connection to the security key broke off. Hold it still against the phone, or plug it in again."

    /* Numbers from the COSE and CTAP specifications. */
    private const val COSE_ES256 = -7
    private const val COSE_EDDSA = -8
    private const val COSE_ALGORITHM = 3
    private const val COSE_X = -2
    private const val COSE_Y = -3
    private const val CRED_PROTECT_VERIFICATION_REQUIRED = 3

    private fun attemptsLeft(session: Ctap2Session): Int? = try {
        ClientPin(session, protocolOf(session)).pinRetries.count
    } catch (_: Exception) {
        null
    }
}
