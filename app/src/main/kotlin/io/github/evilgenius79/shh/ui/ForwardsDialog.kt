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
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Port forwards") },
        text = {
            Column {
                if (forwards.isEmpty()) Text("No forwards are running on this session.")
                for (forward in forwards) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(
                                when (forward.kind) {
                                    PortForward.Kind.LOCAL -> "Local"
                                    PortForward.Kind.REMOTE -> "Remote"
                                    PortForward.Kind.DYNAMIC -> "SOCKS proxy"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "${forward.bindAddress}:${forward.boundPort} → ${forward.destination}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { scope.launch(Dispatchers.IO) { session.removeForward(forward) } }) {
                            AppIcon(R.drawable.ic_close, "Stop forward")
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { adding = true }) { Text("Add") } },
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
                        error = "Could not start the forward: ${e.message}"
                    }
                }
            },
        )
    }
}
