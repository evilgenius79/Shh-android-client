package io.github.evilgenius79.shh.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
        why = stringResource(R.string.seckey_why_login, prompt.target.host, prompt.keyName),
        skipLabel = stringResource(R.string.seckey_log_in_another_way),
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
        title = { Text(stringResource(R.string.seckey_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(why)
                when (val current = step) {
                    is SecurityKeyStep.Present -> {
                        current.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text(howToPresent(LocalResources.current, found.nfc, found.usb, again = current.problem != null || current.pinReady))
                        if (found.nfc == SecurityKeyFinder.Nfc.SWITCHED_OFF) {
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                                Text(stringResource(R.string.seckey_open_nfc_settings))
                            }
                        }
                    }
                    SecurityKeyStep.Working -> Busy(stringResource(R.string.seckey_working))
                    SecurityKeyStep.Touch -> Busy(stringResource(R.string.seckey_touch))
                    is SecurityKeyStep.Pin -> {
                        if (current.wrong) {
                            val left = current.attemptsLeft
                            Text(
                                if (left != null) pluralStringResource(R.plurals.seckey_pin_wrong_tries_left, left, left) else stringResource(R.string.seckey_pin_wrong),
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text(stringResource(R.string.seckey_pin_wanted))
                        }
                        SecretField(pinState, stringResource(R.string.seckey_pin_label), Modifier.fillMaxWidth())
                    }
                    is SecurityKeyStep.Hopeless -> Text(current.message, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    stringResource(R.string.seckey_untested_note),
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
                ) { Text(stringResource(R.string.action_continue)) }
            } else if (skipLabel != null) {
                TextButton(onClick = flow::skip) { Text(skipLabel) }
            }
        },
        dismissButton = { TextButton(onClick = flow::cancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

/**
 * What to do with the key, given how keys can reach this phone. Asking for the key [again] is a
 * text of its own each time rather than a word put in, so that a translation can word it freely.
 */
internal fun howToPresent(resources: Resources, nfc: SecurityKeyFinder.Nfc, usb: Boolean, again: Boolean): String = resources.getString(
    when {
        nfc == SecurityKeyFinder.Nfc.READY && usb -> if (again) R.string.seckey_present_nfc_or_usb_again else R.string.seckey_present_nfc_or_usb
        nfc == SecurityKeyFinder.Nfc.READY -> if (again) R.string.seckey_present_nfc_again else R.string.seckey_present_nfc
        nfc == SecurityKeyFinder.Nfc.SWITCHED_OFF && usb -> if (again) R.string.seckey_present_usb_nfc_off_again else R.string.seckey_present_usb_nfc_off
        nfc == SecurityKeyFinder.Nfc.SWITCHED_OFF -> R.string.seckey_present_nfc_off
        usb -> if (again) R.string.seckey_present_usb_again else R.string.seckey_present_usb
        else -> R.string.seckey_present_impossible
    },
)

/** The activity this context belongs to, if it is shown by one. */
private fun Context.activity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
