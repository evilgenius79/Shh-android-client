package io.github.evilgenius79.shh

import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.fido.ctap.Ctap2Session
import io.github.evilgenius79.shh.security.SecurityKeyClient
import io.github.evilgenius79.shh.security.SecurityKeyException
import io.github.evilgenius79.shh.security.SecurityKeyTask
import io.github.evilgenius79.shh.ssh.SecurityKeyAssertion
import io.github.evilgenius79.shh.ssh.SecurityKeyRequest
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Having a security key sign, against an authenticator made of software. What this cannot show
 * is how real hardware behaves over a real NFC or USB link.
 */
class SecurityKeyClientTest {
    private val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun request(credential: SoftwareAuthenticator.Credential, touch: Boolean = true, verify: Boolean = false) = SecurityKeyRequest(
        keyType = if (credential.ed25519) "sk-ssh-ed25519@openssh.com" else "sk-ecdsa-sha2-nistp256@openssh.com",
        application = credential.application,
        keyHandle = credential.id,
        challenge = challenge,
        touchRequired = touch,
        verificationRequired = verify,
    )

    private fun sign(
        device: SoftwareAuthenticator,
        request: SecurityKeyRequest,
        pin: String? = null,
        state: CommandState? = null,
    ): SecurityKeyAssertion = perform(device, SecurityKeyClient.signing(request), pin, state)

    /** Does [task] with [device] the way the app does with a key that has turned up. */
    private fun <T> perform(device: SoftwareAuthenticator, task: SecurityKeyTask<T>, pin: String? = null, state: CommandState? = null): T =
        SecurityKeyClient.perform({ Ctap2Session(device) }, task, pin?.toCharArray(), { state?.onKeepAliveStatus(CommandState.STATUS_UPNEEDED) }, { state?.waitForCancel(0) == true })

    /** Checks [assertion] the way an SSH server does: see PROTOCOL.u2f in the OpenSSH sources. */
    private fun assertGenuine(assertion: SecurityKeyAssertion, credential: SoftwareAuthenticator.Credential) {
        val signed = MessageDigest.getInstance("SHA-256").digest(credential.application.toByteArray()) +
            assertion.flags.toByte() + ByteBuffer.allocate(4).putInt(assertion.counter.toInt()).array() + challenge
        val valid = Signature.getInstance(if (credential.ed25519) "Ed25519" else "SHA256withECDSA").run {
            initVerify(credential.pair.public)
            update(signed)
            verify(assertion.signature)
        }
        assertTrue(valid, "the signature is the key's, over the application, the flags, the counter and the challenge")
    }

    @Test
    fun aKeySignsWhenItIsTouched() {
        for (ed25519 in listOf(true, false)) {
            val device = SoftwareAuthenticator()
            val credential = device.addCredential(ed25519)
            val first = sign(device, request(credential))
            assertGenuine(first, credential)
            assertEquals(SecurityKeyAssertion.USER_PRESENT, first.flags, "touched, and no PIN was involved")
            assertEquals(1L, first.counter)
            assertEquals(if (ed25519) 64 else first.signature.size, first.signature.size)
            assertEquals(2L, sign(device, request(credential)).counter, "the key counts its signatures")
            assertTrue(0x06 !in device.commands, "no PIN, so nothing was said about one")
            assertEquals(emptyMap(), device.lastOptions, "and the key was left to its own defaults")
        }
    }

    @Test
    fun aKeyThatIsNotTouchedGivesUpAndSaysSo() {
        val device = SoftwareAuthenticator().apply { touched = false }
        val failure = assertFailsWith<SecurityKeyException.Failed> { sign(device, request(device.addCredential(true))) }
        assertEquals("The security key was not touched in time.", failure.message)
        assertTrue(failure.retry)
    }

    @Test
    fun aKeyMadeToSignWithoutATouchIsNotAskedForOne() {
        val device = SoftwareAuthenticator().apply { touched = false }
        val credential = device.addCredential(true)
        val assertion = sign(device, request(credential, touch = false))
        assertEquals(mapOf("up" to false), device.lastOptions)
        assertEquals(0, assertion.flags, "and the signature says that nobody touched it")
        assertGenuine(assertion, credential)
    }

    @Test
    fun theWrongSecurityKeyIsRecognisedAsSuch() {
        val theirs = SoftwareAuthenticator()
        val mine = SoftwareAuthenticator()
        val credential = mine.addCredential(true)
        theirs.addCredential(true)
        val failure = assertFailsWith<SecurityKeyException.Failed> { sign(theirs, request(credential)) }
        assertEquals("This is not the security key that this SSH key belongs to.", failure.message)
        assertTrue(failure.retry, "presenting the right one will work")
    }

    @Test
    fun aKeyThatNeedsItsPinAsksForItAndSignsOnceItIsRight() {
        // Both ways of proving the PIN, and keys from before and since CTAP 2.1.
        for ((protocols, permissions) in listOf(listOf(2, 1) to true, listOf(1) to true, listOf(1) to false, listOf(2) to true, emptyList<Int>() to false)) {
            val offered = protocols.ifEmpty { listOf(1) }
            val device = SoftwareAuthenticator(pinProtocols = offered, pin = "246810", tokensWithPermissions = permissions)
            val credential = device.addCredential(true)
            val needed = request(credential, verify = true)

            val asked = assertFailsWith<SecurityKeyException.PinNeeded> { sign(device, needed) }
            assertTrue(!asked.wrong)
            assertEquals(0, device.signatures, "nothing is signed before the PIN is there")

            val wrong = assertFailsWith<SecurityKeyException.PinNeeded> { sign(device, needed, pin = "135790") }
            assertTrue(wrong.wrong)
            assertEquals(7, wrong.attemptsLeft, "the key says how many tries are left")
            assertEquals("That PIN is not right. 7 more tries before the key locks itself.", wrong.message)

            val assertion = sign(device, needed, pin = "246810")
            assertGenuine(assertion, credential)
            assertEquals(SecurityKeyAssertion.USER_PRESENT or SecurityKeyAssertion.USER_VERIFIED, assertion.flags, "with $offered: touched and verified")
            assertEquals(8, device.pinRetries, "the right PIN restores the tries")
        }
    }

    @Test
    fun aKeyWithoutNeedForThePinSignsWithoutItEvenIfOneIsSet() {
        val device = SoftwareAuthenticator(pin = "246810")
        val credential = device.addCredential(false)
        val assertion = sign(device, request(credential))
        assertEquals(SecurityKeyAssertion.USER_PRESENT, assertion.flags)
        assertGenuine(assertion, credential)
    }

    @Test
    fun aKeyThatAlwaysWantsItsPinGetsIt() {
        val device = SoftwareAuthenticator(pin = "246810", alwaysUv = true)
        val credential = device.addCredential(true)
        // The SSH key does not ask for the PIN, but this security key does for everything.
        assertTrue(!assertFailsWith<SecurityKeyException.PinNeeded> { sign(device, request(credential)) }.wrong)
        assertEquals(5, sign(device, request(credential), pin = "246810").flags)
    }

    @Test
    fun aKeyWithAFingerprintReaderVerifiesByItself() {
        val device = SoftwareAuthenticator(fingerprint = true)
        val credential = device.addCredential(true)
        val assertion = sign(device, request(credential, verify = true))
        assertEquals(mapOf("uv" to true), device.lastOptions)
        assertEquals(5, assertion.flags)
        assertGenuine(assertion, credential)
    }

    @Test
    fun aKeyThatMustVerifyButHasNoPinSaysWhatToDo() {
        val device = SoftwareAuthenticator()
        val failure = assertFailsWith<SecurityKeyException.Failed> { sign(device, request(device.addCredential(true), verify = true)) }
        assertTrue("has none set" in failure.message!!, failure.message)
        assertTrue(!failure.retry)
        val given = assertFailsWith<SecurityKeyException.Failed> { sign(device, request(device.credentials[0]), pin = "1234") }
        assertTrue("no PIN set" in given.message!!, given.message)
    }

    @Test
    fun tooManyWrongPinsLockTheKeyAndTheUserIsTold() {
        val device = SoftwareAuthenticator(pin = "246810")
        val needed = request(device.addCredential(true), verify = true)
        assertEquals(7, assertFailsWith<SecurityKeyException.PinNeeded> { sign(device, needed, "000000") }.attemptsLeft)
        assertEquals(6, assertFailsWith<SecurityKeyException.PinNeeded> { sign(device, needed, "000001") }.attemptsLeft)
        // Three in a row: the key wants to be taken away and presented again.
        val third = assertFailsWith<SecurityKeyException.Failed> { sign(device, needed, "000002") }
        assertTrue("present it again" in third.message!!, third.message)
        assertTrue(third.retry)
        // And once the tries are used up, only a reset helps.
        device.pinRetries = 0
        val locked = assertFailsWith<SecurityKeyException.Failed> { sign(SoftwareAuthenticator(pin = "246810").also { it.pinRetries = 0; it.credentials += device.credentials }, needed, "246810") }
        assertTrue("locked" in locked.message!! && !locked.retry, locked.message)
    }

    @Test
    fun whileTheKeyWaitsToBeTouchedTheAppIsToldAndCanCallItOff() {
        val device = SoftwareAuthenticator().apply { keepAlives = 3 }
        val credential = device.addCredential(true)
        val seen = ArrayList<Byte>()
        val state = object : CommandState() {
            override fun onKeepAliveStatus(status: Byte) {
                seen.add(status)
            }
        }
        assertGenuine(sign(device, request(credential), state = state), credential)
        assertEquals(listOf(CommandState.STATUS_UPNEEDED), seen, "told once that the key waits for the touch")

        val cancelling = object : CommandState() {
            override fun onKeepAliveStatus(status: Byte) = cancel()
        }
        val failure = assertFailsWith<SecurityKeyException.Failed> { sign(device, request(credential), state = cancelling) }
        assertEquals("Called off.", failure.message)
        assertEquals(1, device.signatures, "the request that was called off left nothing behind")
    }

    @Test
    fun requestsAndAnswersThatDoNotFitOneCommandAreSentInParts() {
        val device = SoftwareAuthenticator(pin = "246810")
        // A handle this long makes the request longer than the 255 bytes one command holds.
        val credential = device.addCredential(false, idLength = 700)
        val assertion = sign(device, request(credential, verify = true), pin = "246810")
        assertGenuine(assertion, credential)
        assertEquals(5, assertion.flags)
    }

    @Test
    fun aDeviceThatIsNoSecurityKeyOrGoesAwayIsExplained() {
        val notFido = object : SoftwareAuthenticatorLike() {
            override fun sendAndReceive(apdu: ByteArray): ByteArray = byteArrayOf(0x6A, 0x82.toByte())
        }
        val failure = assertFailsWith<SecurityKeyException.Failed> { SecurityKeyClient.perform({ Ctap2Session(notFido) }, SecurityKeyTask { _, _, _ -> }, null, {}, { false }) }
        assertEquals("This device does not offer FIDO2. Is it a security key?", failure.message)
        assertTrue(!failure.retry)

        val device = SoftwareAuthenticator()
        val credential = device.addCredential(true)
        val lost = assertFailsWith<SecurityKeyException.Failed> {
            perform(
                device,
                SecurityKeyTask { session, pin, state ->
                    device.removed = true
                    SecurityKeyClient.sign(session, request(credential), pin, state)
                },
            )
        }
        assertTrue("broke off" in lost.message!! && lost.retry, lost.message)
        assertNull(lost.cause?.let { it as? SecurityKeyException })
    }

    // ----- making a key -----

    @Test
    fun aKeyMadeOnTheSecurityKeyIsOneItSignsFor() {
        for (ed25519 in listOf(true, false)) {
            val device = SoftwareAuthenticator()
            val key = perform(device, SecurityKeyClient.creating(ed25519))
            val credential = device.credentials.single()
            assertEquals(ed25519, credential.ed25519)
            assertEquals(if (ed25519) "sk-ssh-ed25519@openssh.com" else "sk-ecdsa-sha2-nistp256@openssh.com", key.publicKey.type)
            // The public key is the one the security key holds the private half of.
            assertEquals(io.github.evilgenius79.shh.ssh.SshKeys.securityKey(ed25519, credential.point, "ssh:", credential.id).publicKey, key.publicKey)
            val info = kotlin.test.assertNotNull(key.securityKey)
            assertEquals("ssh:", info.application)
            assertTrue(info.touchRequired && !info.verificationRequired)
            // What ssh-keygen sends as the owner of such a key.
            assertEquals("openssh", device.lastUser["name"])
            assertTrue((device.lastUser["id"] as ByteArray).contentEquals(ByteArray(32)))

            // The key file that is stored leads back to the same key on the hardware.
            val stored = io.github.evilgenius79.shh.ssh.SshKeys.load(io.github.evilgenius79.shh.ssh.SshKeys.toOpenSshPrivateKey(key, "made-here"))
            assertEquals(key.publicKey, stored.publicKey)
            assertGenuine(sign(device, request(credential)), credential)
        }
    }

    @Test
    fun aSecurityKeyWithAPinMakesNothingWithoutIt() {
        val device = SoftwareAuthenticator(pin = "246810")
        assertTrue(!assertFailsWith<SecurityKeyException.PinNeeded> { perform(device, SecurityKeyClient.creating(true)) }.wrong)
        assertTrue(assertFailsWith<SecurityKeyException.PinNeeded> { perform(device, SecurityKeyClient.creating(true), pin = "000000") }.wrong)
        assertEquals(0, device.credentials.size)
        perform(device, SecurityKeyClient.creating(true), pin = "246810")
        assertEquals(1, device.credentials.size)
        assertEquals(1, device.credentials.single().protection, "an ordinary key is not guarded by the PIN afterwards")
    }

    @Test
    fun aKeyMadeToNeedThePinIsGuardedByTheSecurityKeyToo() {
        val device = SoftwareAuthenticator(pin = "246810")
        val key = perform(device, SecurityKeyClient.creating(true, verificationRequired = true), pin = "246810")
        assertTrue(key.securityKey!!.verificationRequired)
        val credential = device.credentials.single()
        assertEquals(3, credential.protection, "the security key itself will not use it without the PIN")
        // The app asks for the PIN, because the key file says so...
        assertFailsWith<SecurityKeyException.PinNeeded> { sign(device, request(credential, verify = true)) }
        assertEquals(5, sign(device, request(credential, verify = true), pin = "246810").flags)
        // ...and someone who leaves that out gets nowhere with the security key.
        val sneaked = assertFailsWith<SecurityKeyException.Failed> { sign(device, request(credential, verify = false)) }
        assertEquals("This is not the security key that this SSH key belongs to.", sneaked.message)

        // A security key that cannot guard credentials still makes the key; the app keeps asking for the PIN.
        val plain = SoftwareAuthenticator(pin = "246810", guardsCredentials = false)
        assertTrue(perform(plain, SecurityKeyClient.creating(false, verificationRequired = true), pin = "246810").securityKey!!.verificationRequired)
        // And without any PIN set there is nothing to ask for.
        val none = assertFailsWith<SecurityKeyException.Failed> { perform(SoftwareAuthenticator(), SecurityKeyClient.creating(true, verificationRequired = true)) }
        assertTrue("with a PIN set" in none.message!! && !none.retry, none.message)
    }

    @Test
    fun aSecurityKeyThatCannotMakeEd25519SaysToTryEcdsa() {
        val old = SoftwareAuthenticator(makesEd25519 = false)
        val failure = assertFailsWith<SecurityKeyException.Failed> { perform(old, SecurityKeyClient.creating(true)) }
        assertEquals("This security key cannot make keys of this kind. Older ones only know ECDSA.", failure.message)
        assertTrue(!failure.retry)
        assertEquals("sk-ecdsa-sha2-nistp256@openssh.com", perform(old, SecurityKeyClient.creating(false)).publicKey.type)
    }

    @Test
    fun aKeyIsNotMadeUnlessTheSecurityKeyIsTouched() {
        val device = SoftwareAuthenticator().apply { touched = false }
        assertEquals("The security key was not touched in time.", assertFailsWith<SecurityKeyException.Failed> { perform(device, SecurityKeyClient.creating(true)) }.message)
        assertEquals(0, device.credentials.size)
    }

    @Test
    fun anAnswerThatIsNotAKeyIsNotTakenForOne() {
        assertFailsWith<SecurityKeyException.Failed> { SecurityKeyClient.keyOf(ByteArray(37), true, "ssh:", false) }
        assertFailsWith<SecurityKeyException.Failed> { SecurityKeyClient.keyOf(ByteArray(5), true, "ssh:", false) }
        // A key of the other kind than was asked for.
        val device = SoftwareAuthenticator()
        val failure = assertFailsWith<SecurityKeyException.Failed> {
            perform(device, SecurityKeyTask { session, pin, state -> SecurityKeyClient.create(session, false, "ssh:", false, pin, state).also { } }).let { made ->
                // Made as ECDSA; reading the same answer as Ed25519 must not work.
                SecurityKeyClient.keyOf(answerFor(device), true, "ssh:", false)
            }
        }
        assertTrue("another kind" in failure.message!!, failure.message)
    }

    /** The authenticator data a security key would answer with for its newest credential. */
    private fun answerFor(device: SoftwareAuthenticator): ByteArray {
        val credential = device.credentials.last()
        val publicKey: Map<Int, Any> = linkedMapOf(1 to 2, 3 to -7, -1 to 1, -2 to credential.point.copyOfRange(1, 33), -3 to credential.point.copyOfRange(33, 65))
        return ByteArray(32) + byteArrayOf(0x41) + ByteArray(4) + ByteArray(16) +
            byteArrayOf((credential.id.size shr 8).toByte(), credential.id.size.toByte()) + credential.id + com.yubico.yubikit.fido.Cbor.encode(publicKey)
    }

    @Test
    fun whatTheKeyReportsIsReadFromTheRightPlaces() {
        val data = ByteArray(32) { 0x55 } + byteArrayOf(0x05, 0x00, 0x01, 0x02, 0x03) + ByteArray(8)
        val assertion = SecurityKeyClient.assertionOf(data, byteArrayOf(9, 9))
        assertEquals(5, assertion.flags)
        assertEquals(0x010203L, assertion.counter)
        val large = SecurityKeyClient.assertionOf(ByteArray(32) + byteArrayOf(0x01, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xfe.toByte()), ByteArray(0))
        assertEquals(0xfffffffeL, large.counter, "the counter has no sign")
        assertFailsWith<SecurityKeyException.Failed> { SecurityKeyClient.assertionOf(ByteArray(36), ByteArray(0)) }
    }
}

/** The parts of a smart card connection a test does not care about. */
abstract class SoftwareAuthenticatorLike : com.yubico.yubikit.core.smartcard.SmartCardConnection {
    override fun getTransport() = com.yubico.yubikit.core.Transport.NFC

    override fun isExtendedLengthApduSupported() = false

    override fun getAtr() = ByteArray(0)

    override fun close() = Unit
}
