package io.github.evilgenius79.shh.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.security.SecurityKeyClient
import io.github.evilgenius79.shh.security.SecurityKeyFinder
import io.github.evilgenius79.shh.security.SecurityKeyFlow
import io.github.evilgenius79.shh.security.SecurityKeyOutcome
import io.github.evilgenius79.shh.security.SecurityKeyStep
import io.github.evilgenius79.shh.security.SecurityKeyTask
import io.github.evilgenius79.shh.security.YubiKitFinder
import io.github.evilgenius79.shh.session.SessionPrompt
import io.github.evilgenius79.shh.ssh.SecurityKeyAnswer

/**
 * Shown while a login waits for a security key: says how to present the key, asks for its PIN
 * when it wants one, and lets the user go on without the key or give up.
 *
 * @param finder how keys are found. The default looks on NFC and USB; tests bring their own.
 */
@Composable
fun SecurityKeyDialog(prompt: SessionPrompt.SecurityKey, finder: SecurityKeyFinder? = null) {
    SecurityKeyDialog(
        key = prompt,
        task = remember(prompt) { SecurityKeyClient.signing(prompt.request) },
        why = "Logging in to ${prompt.target.host} takes the security key that holds \u201c${prompt.keyName}\u201d.",
        skipLabel = "Log in another way",
        finder = finder,
        onDone = { outcome ->
            prompt.respond(
                when (outcome) {
                    is SecurityKeyOutcome.Done -> SecurityKeyAnswer.Signed(outcome.value)
                    SecurityKeyOutcome.Skipped -> SecurityKeyAnswer.Skip
                    SecurityKeyOutcome.Cancelled -> SecurityKeyAnswer.Cancel
                },
            )
        },
    )
}

/**
 * The dialog for anything that takes a security key: [task] is what to do with the key, [why]
 * tells the user what it is wanted for.
 *
 * @param key what this dialog is about. A new value starts over with a new search for keys.
 * @param skipLabel the button for carrying on without the key, or null where there is no such thing.
 */
@Composable
fun <T> SecurityKeyDialog(
    key: Any,
    task: SecurityKeyTask<T>,
    why: String,
    skipLabel: String?,
    finder: SecurityKeyFinder? = null,
    onDone: (SecurityKeyOutcome<T>) -> Unit,
) {
    val context = LocalContext.current
    val found = remember(key) { finder ?: context.activity()?.let(::YubiKitFinder) }
    val done by rememberUpdatedState(onDone)
    if (found == null) {
        // No screen to find keys from. Nothing sensible is left but to go on without the key.
        DisposableEffect(key) {
            done(SecurityKeyOutcome.Skipped)
            onDispose { }
        }
        return
    }
    val flow = remember(key) { SecurityKeyFlow(found, task) { outcome -> done(outcome) } }
    DisposableEffect(flow) {
        flow.start()
        // Leaving the screen without an answer stops the search; the question stays open and
        // is put again when the screen comes back.
        onDispose { flow.abandon() }
    }
    val step by flow.step.collectAsState()
    val pinState = rememberTextFieldState()
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        // Not dismissed by a tap beside it: holding a key against the phone makes for stray taps.
        onDismissRequest = {},
        icon = { AppIcon(R.drawable.ic_key, null) },
        title = { Text("Security key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(why)
                when (val current = step) {
                    is SecurityKeyStep.Present -> {
                        current.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text(howToPresent(found.nfc, found.usb, again = current.problem != null || current.pinReady))
                        if (found.nfc == SecurityKeyFinder.Nfc.SWITCHED_OFF) {
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                                Text("Open the NFC settings")
                            }
                        }
                    }
                    SecurityKeyStep.Working -> Busy("Talking to the key. Keep it where it is.")
                    SecurityKeyStep.Touch -> Busy("Touch the key now. Its light is blinking.")
                    is SecurityKeyStep.Pin -> {
                        if (current.wrong) {
                            val left = current.attemptsLeft
                            Text(
                                "That PIN is not right." + if (left != null) " $left more ${if (left == 1) "try" else "tries"} before the key locks itself." else "",
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text("This key wants its PIN first. That is the PIN of the security key itself, not a password of the server.")
                        }
                        SecretField(pinState, "PIN of the security key", Modifier.fillMaxWidth())
                    }
                    is SecurityKeyStep.Hopeless -> Text(current.message, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Support for security keys is new and has only been tried against a stand-in made of software, not yet with a real key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = quiet,
                )
            }
        },
        confirmButton = {
            if (step is SecurityKeyStep.Pin) {
                TextButton(
                    enabled = pinState.text.length >= 4,
                    onClick = {
                        flow.pinEntered(pinState.text.toString().toCharArray())
                        pinState.edit { replace(0, length, "") }
                    },
                ) { Text("Continue") }
            } else if (skipLabel != null) {
                TextButton(onClick = flow::skip) { Text(skipLabel) }
            }
        },
        dismissButton = { TextButton(onClick = flow::cancel) { Text("Cancel") } },
    )
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

/** What to do with the key, given how keys can reach this phone. */
internal fun howToPresent(nfc: SecurityKeyFinder.Nfc, usb: Boolean, again: Boolean): String {
    val once = if (again) " again" else ""
    return when {
        nfc == SecurityKeyFinder.Nfc.READY && usb -> "Hold the key against the back of the phone$once and keep it there, or plug it in and touch it."
        nfc == SecurityKeyFinder.Nfc.READY -> "Hold the key against the back of the phone$once and keep it there."
        nfc == SecurityKeyFinder.Nfc.SWITCHED_OFF && usb -> "Plug the key in$once and touch it. NFC is switched off; with it on, the key can also be held against the phone."
        nfc == SecurityKeyFinder.Nfc.SWITCHED_OFF -> "NFC is switched off. Switch it on to hold the key against the phone."
        usb -> "Plug the key in$once and touch it."
        else -> "This phone has neither NFC nor a USB port that a key could use."
    }
}

/** The activity this context belongs to, if it is shown by one. */
private fun Context.activity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
