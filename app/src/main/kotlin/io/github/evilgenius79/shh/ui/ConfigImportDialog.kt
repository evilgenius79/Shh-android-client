package io.github.evilgenius79.shh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.data.ConfigImport
import io.github.evilgenius79.shh.data.Host

/** The longest configuration file that is read. Real ones are a few kilobytes. */
internal const val MAX_CONFIG_BYTES = 1024 * 1024

/**
 * Shows what importing an OpenSSH configuration file would add, asks for what the file does not
 * say, and hands the hosts to [onImport] when the user agrees.
 */
@Composable
fun ConfigImportDialog(plan: ConfigImport.Plan, onImport: (List<Host>) -> Unit, onDismiss: () -> Unit) {
    val userState = rememberTextFieldState()
    val user = userState.text.toString().trim()
    val userNeeded = plan.withoutUser.isNotEmpty()
    val small = MaterialTheme.typography.bodySmall
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from an SSH config") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (plan.hosts.isEmpty()) {
                    Text(if (plan.alreadySaved.isEmpty()) "This file names no hosts." else "Every host this file names is saved already.")
                } else {
                    Text(if (plan.hosts.size == 1) "One host will be added:" else "${plan.hosts.size} hosts will be added:")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (host in plan.hosts) {
                            Column {
                                Text(host.title, style = MaterialTheme.typography.bodyMedium)
                                if (host.name.isNotBlank()) Text(host.address, style = small, color = quiet)
                            }
                        }
                    }
                }
                if (plan.alreadySaved.isNotEmpty() && plan.hosts.isNotEmpty()) {
                    Text("Saved already and left as they are: ${plan.alreadySaved.joinToString(", ")}", style = small, color = quiet)
                }
                if (userNeeded) {
                    OutlinedTextField(
                        state = userState,
                        label = { Text("User name") },
                        supportingText = {
                            Text(
                                "The file names no user for ${plan.withoutUser.joinToString(", ")}. " +
                                    "On a computer ssh would use your login name there.",
                            )
                        },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                for (remark in plan.remarks) Text(remark, style = small, color = quiet)
            }
        },
        confirmButton = {
            if (plan.hosts.isNotEmpty()) {
                TextButton(
                    enabled = !userNeeded || (user.isNotEmpty() && user.none { it.isWhitespace() }),
                    onClick = { onImport(plan.hostsFor(user)) },
                ) { Text(if (plan.hosts.size == 1) "Add the host" else "Add ${plan.hosts.size} hosts") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = if (plan.hosts.isNotEmpty()) ({ TextButton(onClick = onDismiss) { Text("Cancel") } }) else null,
    )
}
