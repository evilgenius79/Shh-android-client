package io.github.evilgenius79.shh.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A security key that has turned up, and the way to do something with it. */
interface SecurityKeyLink {
    /**
     * @param onWaitingForTouch called when the key says that it waits to be touched.
     * @param calledOff asked while the key waits; true ends the waiting.
     */
    @Throws(SecurityKeyException::class)
    fun <T> perform(task: SecurityKeyTask<T>, pin: CharArray?, onWaitingForTouch: () -> Unit, calledOff: () -> Boolean): T
}

/** How the business with a security key ended. */
sealed interface SecurityKeyOutcome<out T> {
    class Done<T>(val value: T) : SecurityKeyOutcome<T>

    /** The key is not at hand: carry on without it. */
    data object Skipped : SecurityKeyOutcome<Nothing>

    /** The user gave up. */
    data object Cancelled : SecurityKeyOutcome<Nothing>
}

/** Looks for security keys that are held against the phone or plugged into it. */
interface SecurityKeyFinder {
    enum class Nfc { READY, SWITCHED_OFF, ABSENT }

    /** How a key can reach this phone right now, for telling the user what to do. */
    val nfc: Nfc
    val usb: Boolean

    /** Starts looking. [onKey] is called for every key that turns up, on a thread of the finder's choosing. */
    fun start(onKey: (SecurityKeyLink) -> Unit)

    fun stop()
}

/** Where the business with the security key stands, for the screen to show. */
sealed interface SecurityKeyStep {
    /** Waiting for a key to be presented. [problem] says why the last try came to nothing. */
    data class Present(val problem: String? = null, val pinReady: Boolean = false) : SecurityKeyStep

    /** A key is there and is being talked to. */
    data object Working : SecurityKeyStep

    /** The key waits to be touched. */
    data object Touch : SecurityKeyStep

    /** The key wants its PIN. [wrong] when one was tried and refused. */
    data class Pin(val wrong: Boolean, val attemptsLeft: Int?) : SecurityKeyStep

    /** It cannot work, and presenting the key again will not change that. */
    data class Hopeless(val message: String) : SecurityKeyStep
}

/**
 * Gets one thing done with a security key, such as a signature: waits for the key, asks for its
 * PIN when it wants one, and tells [onDone] how it ended. Everything the screen needs is in [step].
 */
class SecurityKeyFlow<T>(
    private val finder: SecurityKeyFinder,
    private val task: SecurityKeyTask<T>,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val onDone: (SecurityKeyOutcome<T>) -> Unit,
) {
    private val state = MutableStateFlow<SecurityKeyStep>(SecurityKeyStep.Present())
    val step: StateFlow<SecurityKeyStep> get() = state

    private val done = AtomicBoolean(false)
    private val calledOff = AtomicBoolean(false)

    @Volatile
    private var pin: CharArray? = null

    fun start() = look()

    private fun look() {
        try {
            finder.start(::found)
        } catch (e: RuntimeException) {
            // Other people's code under a system service: say what happened instead of crashing.
            state.value = SecurityKeyStep.Hopeless("Looking for security keys failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Stops looking without an answer, for when the screen that shows this goes away. The
     * question stays open: a new flow can take it up.
     */
    fun abandon() {
        if (done.get()) return
        calledOff.set(true)
        finder.stop()
        forgetPin()
        worker.shutdown()
    }

    /** The user typed the key's PIN. With a key that is plugged in, it is tried at once. */
    fun pinEntered(entered: CharArray) {
        if (done.get()) return
        pin = entered
        state.value = SecurityKeyStep.Present(pinReady = true)
        // A key that stayed plugged in is reported again when the search starts afresh.
        finder.stop()
        look()
    }

    /** The key is not at hand: carry on without it. */
    fun skip() = finish(SecurityKeyOutcome.Skipped)

    /** Give up. */
    fun cancel() = finish(SecurityKeyOutcome.Cancelled)

    private fun found(link: SecurityKeyLink) {
        worker.execute {
            if (done.get()) return@execute
            // A second key while one is being asked, or while the PIN is being typed, has to wait.
            val before = state.value
            if (before !is SecurityKeyStep.Present) return@execute
            state.value = SecurityKeyStep.Working
            try {
                val result = link.perform(task, pin, { if (!done.get()) state.value = SecurityKeyStep.Touch }, calledOff::get)
                finish(SecurityKeyOutcome.Done(result))
            } catch (e: SecurityKeyException.PinNeeded) {
                forgetPin()
                if (!done.get()) state.value = SecurityKeyStep.Pin(e.wrong, e.attemptsLeft)
            } catch (e: SecurityKeyException.Failed) {
                if (!done.get()) state.value = if (e.retry) SecurityKeyStep.Present(e.message, pinReady = pin != null) else SecurityKeyStep.Hopeless(e.message.orEmpty())
            } catch (e: RuntimeException) {
                // The library that talks to the key, or the key itself, did something unforeseen.
                if (!done.get()) state.value = SecurityKeyStep.Present("That did not work: ${e.message ?: e.javaClass.simpleName}", pinReady = pin != null)
            }
        }
    }

    private fun finish(answer: SecurityKeyOutcome<T>) {
        if (!done.compareAndSet(false, true)) return
        calledOff.set(true)
        finder.stop()
        forgetPin()
        worker.shutdown()
        onDone(answer)
    }

    private fun forgetPin() {
        pin?.fill('\u0000')
        pin = null
    }
}
