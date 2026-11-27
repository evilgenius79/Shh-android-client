package io.github.evilgenius79.shh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.ForwardRule
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.PortForward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Shows the port forwards running on a session and lets the user add or stop them. */
@Composable
fun SessionForwardsDialog(session: TerminalSession, onDismiss: () -> Unit) {
    val forwards by session.forwards.collectAsStateCompat()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.forwards_title)) },
        text = {
            Column {
                if (forwards.isEmpty()) Text(stringResource(R.string.forwards_none))
                for (forward in forwards) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(
                                stringResource(
                                    when (forward.kind) {
                                        PortForward.Kind.LOCAL -> R.string.forwards_kind_local
                                        PortForward.Kind.REMOTE -> R.string.forwards_kind_remote
                                        PortForward.Kind.DYNAMIC -> R.string.forwards_kind_dynamic
                                    },
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "${forward.bindAddress}:${forward.boundPort} → ${forward.destination}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { scope.launch(Dispatchers.IO) { session.removeForward(forward) } }) {
                            AppIcon(R.drawable.ic_close, stringResource(R.string.forwards_stop))
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = { TextButton(onClick = { adding = true }) { Text(stringResource(R.string.action_add)) } },
    )

    if (adding) {
        ForwardDialog(
            initial = ForwardRule(id = "", kind = ForwardKind.LOCAL, bindPort = 8080, destinationHost = "localhost", destinationPort = 80),
            onDismiss = { adding = false },
            onSave = { rule ->
                adding = false
                error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            session.addForward(rule.kind, rule.bindAddress, rule.bindPort, rule.destinationHost, rule.destinationPort)
                        }
                    } catch (e: IOException) {
                        error = resources.getString(R.string.forwards_start_failed, e.message)
                    }
                }
            },
        )
    }
}
