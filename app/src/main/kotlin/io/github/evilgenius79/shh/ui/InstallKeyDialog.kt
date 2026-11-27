package io.github.evilgenius79.shh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.Identity
import io.github.evilgenius79.shh.session.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The shell command that appends [publicKeyLine] to the user's `authorized_keys` unless it is
 * already there, creating `~/.ssh` with the permissions sshd insists on. The equivalent of
 * `ssh-copy-id`.
 */
fun installKeyCommand(publicKeyLine: String): String {
    // Single quotes keep the shell from interpreting anything inside the key line; a quote in
    // the comment is the only character that needs care.
    val quoted = "'" + publicKeyLine.replace("'", "'\\''") + "'"
    return "umask 077 && mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
        "(grep -qxF $quoted ~/.ssh/authorized_keys || printf '%s\\n' $quoted >> ~/.ssh/authorized_keys)"
}

/** Lets the user pick one of their keys and installs its public half on the connected server. */
@Composable
fun InstallKeyDialog(session: TerminalSession, identities: List<Identity>, onDismiss: () -> Unit) {
    // The texts about how it went are put together in a coroutine, out of reach of stringResource().
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.installkey_title)) },
        text = {
            Column {
                val status = result
                if (status != null) {
                    Text(status)
                } else {
                    Text(stringResource(R.string.installkey_text, session.host.title), style = MaterialTheme.typography.bodyMedium)
                    for (identity in identities) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !working) {
                                    working = true
                                    scope.launch {
                                        result = try {
                                            val outcome = withContext(Dispatchers.IO) {
                                                val connection = session.connection
                                                    ?: throw IOException(resources.getString(R.string.installkey_reason_not_connected))
                                                connection.execute(installKeyCommand("${identity.publicKey} ${identity.name}"))
                                            }
                                            val complaint = outcome.stderr.trim()
                                            when {
                                                outcome.isSuccess -> resources.getString(R.string.installkey_installed, identity.name)
                                                complaint.isNotEmpty() -> resources.getString(R.string.installkey_server_error, complaint)
                                                else -> resources.getString(R.string.installkey_server_error_status, outcome.exitStatus)
                                            }
                                        } catch (e: IOException) {
                                            resources.getString(R.string.installkey_failed, e.message)
                                        }
                                        working = false
                                    }
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(identity.name, style = MaterialTheme.typography.bodyLarge)
                            Text(identity.algorithm, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = onDismiss) {
                Text(stringResource(if (result != null) R.string.installkey_done else R.string.action_cancel))
            }
        },
    )
}
