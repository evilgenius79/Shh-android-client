package io.github.evilgenius79.shh

import com.yubico.yubikit.fido.ctap.Ctap2Session
import io.github.evilgenius79.shh.security.SecurityKeyClient
import io.github.evilgenius79.shh.security.SecurityKeyFinder
import io.github.evilgenius79.shh.security.SecurityKeyFlow
import io.github.evilgenius79.shh.security.SecurityKeyLink
import io.github.evilgenius79.shh.security.SecurityKeyOutcome
import io.github.evilgenius79.shh.security.SecurityKeyStep
import io.github.evilgenius79.shh.security.SecurityKeyTask
import io.github.evilgenius79.shh.ssh.SecurityKeyAssertion
import io.github.evilgenius79.shh.ssh.SecurityKeyRequest
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A finder for tests: keys are presented by calling [tap], or stay plugged in with [pluggedIn]. */
class TestKeyFinder(
    override val nfc: SecurityKeyFinder.Nfc = SecurityKeyFinder.Nfc.READY,
    override val usb: Boolean = true,
) : SecurityKeyFinder {
    private var onKey: ((SecurityKeyLink) -> Unit)? = null
    var starts = 0
    var stops = 0

    /** A key in the USB port: it is reported whenever the search starts. */
    var pluggedIn: SoftwareAuthenticator? = null

    val isLooking: Boolean get() = onKey != null

    override fun start(onKey: (SecurityKeyLink) -> Unit) {
        starts++
        this.onKey = onKey
        pluggedIn?.let(::tap)
    }

    override fun stop() {
        stops++
        onKey = null
    }

    /** Holds [device] against the phone. */
    fun tap(device: SoftwareAuthenticator) {
        onKey?.invoke(object : SecurityKeyLink {
            override fun <T> perform(task: SecurityKeyTask<T>, pin: CharArray?, onWaitingForTouch: () -> Unit, calledOff: () -> Boolean): T =
                SecurityKeyClient.perform({ Ctap2Session(device) }, task, pin, onWaitingForTouch, calledOff)
        })
    }
}

/** Runs what it is given at once, on the calling thread, so that tests need not wait. */
class InlineExecutor : AbstractExecutorService() {
    private var down = false

    override fun execute(command: Runnable) = command.run()

    override fun shutdown() {
        down = true
    }

    override fun shutdownNow(): List<Runnable> = emptyList<Runnable>().also { down = true }

    override fun isShutdown() = down

    override fun isTerminated() = down

    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

/** The course of getting a signature out of a security key, as the dialog drives it. */
class SecurityKeyFlowTest {
    private val finder = TestKeyFinder()
    private val answers = ArrayList<SecurityKeyOutcome<SecurityKeyAssertion>>()

    private fun request(credential: SoftwareAuthenticator.Credential, verify: Boolean = false) = SecurityKeyRequest(
        if (credential.ed25519) "sk-ssh-ed25519@openssh.com" else "sk-ecdsa-sha2-nistp256@openssh.com",
        credential.application, credential.id, ByteArray(32) { it.toByte() }, touchRequired = true, verificationRequired = verify,
    )

    private fun flow(request: SecurityKeyRequest) = SecurityKeyFlow(finder, SecurityKeyClient.signing(request), InlineExecutor()) { answers.add(it) }.also { it.start() }

    private fun signed(): SecurityKeyAssertion = assertIs<SecurityKeyOutcome.Done<SecurityKeyAssertion>>(answers.single()).value

    @Test
    fun aKeyHeldAgainstThePhoneSignsAndThatIsTheEndOfIt() {
        val device = SoftwareAuthenticator()
        val flow = flow(request(device.addCredential(true)))
        assertEquals(SecurityKeyStep.Present(), flow.step.value)
        assertTrue(finder.isLooking)
        finder.tap(device)
        assertEquals(SecurityKeyAssertion.USER_PRESENT, signed().flags)
        assertTrue(!finder.isLooking, "the search stops once there is an answer")
        // Whatever happens afterwards changes nothing.
        flow.cancel()
        flow.skip()
        assertEquals(1, answers.size)
    }

    @Test
    fun aKeyThatWantsItsPinIsAskedAgainOnceThePinIsTyped() {
        val device = SoftwareAuthenticator(pin = "246810")
        val flow = flow(request(device.addCredential(true), verify = true))
        finder.tap(device)
        assertEquals(SecurityKeyStep.Pin(wrong = false, attemptsLeft = null), flow.step.value)
        finder.tap(device)
        assertEquals(SecurityKeyStep.Pin(wrong = false, attemptsLeft = null), flow.step.value, "holding the key there again changes nothing while the PIN is awaited")

        flow.pinEntered("135790".toCharArray())
        assertEquals(SecurityKeyStep.Present(pinReady = true), flow.step.value, "over NFC the key has to be held against the phone once more")
        finder.tap(device)
        assertEquals(SecurityKeyStep.Pin(wrong = true, attemptsLeft = 7), flow.step.value)

        flow.pinEntered("246810".toCharArray())
        finder.tap(device)
        assertEquals(SecurityKeyAssertion.USER_PRESENT or SecurityKeyAssertion.USER_VERIFIED, signed().flags)
    }

    @Test
    fun aKeyThatStaysPluggedInIsUsedAsSoonAsThePinIsTyped() {
        val device = SoftwareAuthenticator(pin = "246810")
        finder.pluggedIn = device
        val flow = flow(request(device.addCredential(false), verify = true))
        assertIs<SecurityKeyStep.Pin>(flow.step.value)
        flow.pinEntered("246810".toCharArray())
        assertEquals(5, signed().flags)
        assertEquals(2, finder.starts, "the search was started afresh to find the key that was still there")
    }

    @Test
    fun theWrongKeyIsSaidToBeWrongAndTheRightOneStillWorks() {
        val mine = SoftwareAuthenticator()
        val someoneElses = SoftwareAuthenticator().apply { addCredential(true) }
        val flow = flow(request(mine.addCredential(true)))
        finder.tap(someoneElses)
        assertEquals(SecurityKeyStep.Present("This is not the security key that this SSH key belongs to."), flow.step.value)
        assertTrue(finder.isLooking)
        finder.tap(mine)
        signed()
    }

    @Test
    fun whatCannotWorkIsNotOfferedAgain() {
        val device = SoftwareAuthenticator()
        val flow = flow(request(device.addCredential(true), verify = true))
        finder.tap(device)
        val step = assertIs<SecurityKeyStep.Hopeless>(flow.step.value)
        assertTrue("has none set" in step.message, step.message)
        finder.tap(device)
        assertIs<SecurityKeyStep.Hopeless>(flow.step.value)
        flow.skip()
        assertEquals(listOf<SecurityKeyOutcome<SecurityKeyAssertion>>(SecurityKeyOutcome.Skipped), answers)
    }

    @Test
    fun theUserCanGoOnWithoutTheKeyOrGiveUp() {
        val device = SoftwareAuthenticator()
        val credential = device.addCredential(true)
        flow(request(credential)).skip()
        assertEquals(listOf<SecurityKeyOutcome<SecurityKeyAssertion>>(SecurityKeyOutcome.Skipped), answers)
        answers.clear()
        val second = flow(request(credential))
        second.cancel()
        assertEquals(listOf<SecurityKeyOutcome<SecurityKeyAssertion>>(SecurityKeyOutcome.Cancelled), answers)
        finder.tap(device)
        assertEquals(0, device.signatures, "a key presented afterwards is not asked for anything")
    }

    @Test
    fun whileTheKeyWaitsForTheTouchTheScreenSaysSo() {
        val device = SoftwareAuthenticator().apply { keepAlives = 2 }
        val flow = flow(request(device.addCredential(true)))
        val seen = ArrayList<SecurityKeyStep>()
        val watcher = Thread {
            // The steps go by on the thread that talks to the key; this one only looks.
            while (!Thread.currentThread().isInterrupted && answers.isEmpty()) {
                val now = flow.step.value
                if (seen.lastOrNull() != now) seen.add(now)
            }
        }
        watcher.start()
        finder.tap(device)
        watcher.join(5_000)
        signed()
        assertTrue(SecurityKeyStep.Touch in seen, "saw $seen")
    }

    @Test
    fun aScreenThatGoesAwayLeavesTheQuestionOpen() {
        val device = SoftwareAuthenticator()
        val credential = device.addCredential(true)
        val first = flow(request(credential))
        first.abandon()
        assertTrue(!finder.isLooking)
        assertEquals(emptyList(), answers, "no answer was given")
        // The screen comes back and takes the question up again.
        flow(request(credential))
        finder.tap(device)
        signed()
    }

    @Test
    fun aSearchThatCannotStartIsReportedRatherThanCrashing() {
        val broken = object : SecurityKeyFinder {
            override val nfc = SecurityKeyFinder.Nfc.ABSENT
            override val usb = false

            override fun start(onKey: (SecurityKeyLink) -> Unit) = throw SecurityException("no access to USB")

            override fun stop() = Unit
        }
        val flow = SecurityKeyFlow(broken, SecurityKeyClient.signing(request(SoftwareAuthenticator().addCredential(true))), InlineExecutor()) { answers.add(it) }
        flow.start()
        assertEquals(SecurityKeyStep.Hopeless("Looking for security keys failed: no access to USB"), flow.step.value)
    }
}
