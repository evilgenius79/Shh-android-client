@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.ForwardRule
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.Identity
import io.github.evilgenius79.shh.terminal.ColorScheme

/**
 * @param original the host being edited, or a template for a new one.
 * @param isNew true when saving creates a host rather than replacing one.
 * @param hasSavedPassword whether [original] already has a password stored.
 * @param onSave receives the edited host and the password field: null keeps the stored
 * password, an empty string removes it, anything else replaces it.
 */
@Composable
fun HostEditScreen(
    data: AppData,
    original: Host,
    isNew: Boolean,
    hasSavedPassword: Boolean,
    onSave: (Host, String?) -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(original.name) }
    var address by rememberSaveable { mutableStateOf(original.host) }
    var port by rememberSaveable { mutableStateOf(original.port.toString()) }
    var username by rememberSaveable { mutableStateOf(original.username) }
    var group by rememberSaveable { mutableStateOf(original.group) }
    var password by rememberSaveable { mutableStateOf("") }
    var removePassword by rememberSaveable { mutableStateOf(false) }
    var identityId by rememberSaveable { mutableStateOf(original.identityId) }
    var jumpHostId by rememberSaveable { mutableStateOf(original.jumpHostId) }
    var startupCommand by rememberSaveable { mutableStateOf(original.startupCommand) }
    var colorSchemeId by rememberSaveable { mutableStateOf(original.colorSchemeId) }
    var legacy by rememberSaveable { mutableStateOf(original.allowLegacyAlgorithms) }
    var compression by rememberSaveable { mutableStateOf(original.compression) }
    var keepAlive by rememberSaveable { mutableStateOf(original.keepAliveSeconds.toString()) }
    var forwards by remember { mutableStateOf(original.forwards) }
    var editingForward by remember { mutableStateOf<ForwardRule?>(null) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val keepAliveNumber = keepAlive.toIntOrNull()?.takeIf { it in 0..3600 }
    val addressValid = address.isNotBlank() && address.none { it.isWhitespace() }
    val valid = addressValid && portNumber != null && keepAliveNumber != null && username.isNotBlank()

    fun save() {
        if (!valid) {
            showErrors = true
            return
        }
        val host = original.copy(
            name = name.trim(),
            host = address.trim(),
            port = portNumber,
            username = username.trim(),
            group = group.trim(),
            identityId = identityId,
            jumpHostId = jumpHostId,
            startupCommand = startupCommand.trim(),
            colorSchemeId = colorSchemeId,
            allowLegacyAlgorithms = legacy,
            compression = compression,
            keepAliveSeconds = keepAliveNumber,
            forwards = forwards,
        )
        val passwordChange = when {
            password.isNotEmpty() -> password
            removePassword -> ""
            else -> null
        }
        onSave(host, passwordChange)
    }

    Scaffold(
        topBar = {
            BackTopBar(if (isNew) "New host" else "Edit host", onBack) {
                TextButton(onClick = ::save) { Text("Save") }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            val field = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
            val plainText = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None)

            SectionHeader("Server")
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = field)
            Row(field, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    address,
                    { address = it },
                    label = { Text("Address") },
                    singleLine = true,
                    isError = showErrors && !addressValid,
                    supportingText = if (showErrors && !addressValid) ({ Text("Host name or IP address") }) else null,
                    keyboardOptions = plainText.copy(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = showErrors && portNumber == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            OutlinedTextField(
                username,
                { username = it },
                label = { Text("User name") },
                singleLine = true,
                isError = showErrors && username.isBlank(),
                supportingText = if (showErrors && username.isBlank()) ({ Text("Required") }) else null,
                keyboardOptions = plainText,
                modifier = field,
            )
            OutlinedTextField(group, { group = it }, label = { Text("Group (optional)") }, singleLine = true, modifier = field)

            SectionHeader("Authentication")
            SelectField(
                label = "Key",
                selected = data.identity(identityId),
                options = listOf<Identity?>(null) + data.identities,
                optionLabel = { it?.let { key -> "${key.name} (${key.algorithm})" } ?: "None" },
                onSelect = { identityId = it?.id },
                modifier = field,
            )
            SecretField(
                value = password,
                onValueChange = {
                    password = it
                    if (it.isNotEmpty()) removePassword = false
                },
                label = if (hasSavedPassword && !removePassword) "Password (saved, type to replace)" else "Password (optional)",
                supportingText = "Stored encrypted on this device. Leave empty to be asked when connecting.",
                modifier = field,
            )
            if (hasSavedPassword && !removePassword && password.isEmpty()) {
                TextButton(onClick = { removePassword = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Forget saved password")
                }
            }

            SectionHeader("Connection")
            SelectField(
                label = "Jump host",
                selected = data.host(jumpHostId),
                options = listOf<Host?>(null) + data.hosts.filter { it.id != original.id },
                optionLabel = { it?.title ?: "None (connect directly)" },
                onSelect = { jumpHostId = it?.id },
                modifier = field,
            )
            OutlinedTextField(
                keepAlive,
                { keepAlive = it.filter(Char::isDigit).take(4) },
                label = { Text("Keep-alive interval (seconds, 0 = off)") },
                singleLine = true,
                isError = showErrors && keepAliveNumber == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = field,
            )
            SwitchRow("Compression", "Helps on slow links, costs battery on fast ones", compression, { compression = it })
            SwitchRow(
                "Allow legacy algorithms",
                "Only for old devices that cannot do modern cryptography (SHA-1, CBC, ssh-rsa)",
                legacy,
                { legacy = it },
            )

            SectionHeader("Terminal")
            OutlinedTextField(
                startupCommand,
                { startupCommand = it },
                label = { Text("Run after login (optional)") },
                placeholder = { Text("tmux new -A -s main") },
                singleLine = true,
                keyboardOptions = plainText,
                modifier = field,
            )
            SelectField(
                label = "Colours",
                selected = colorSchemeId,
                options = listOf<String?>(null) + ColorScheme.ALL.map { it.id },
                optionLabel = { id -> if (id == null) "Use default" else ColorScheme.byId(id).name },
                onSelect = { colorSchemeId = it },
                modifier = field,
            )

            SectionHeader("Port forwards")
            for (rule in forwards) {
                Entry(
                    headline = rule.summary,
                    supporting = {
                        Text(
                            when (rule.kind) {
                                ForwardKind.LOCAL -> "Local port to a destination reachable from the server"
                                ForwardKind.REMOTE -> "Port on the server to a destination reachable from this device"
                                ForwardKind.DYNAMIC -> "SOCKS proxy on this device"
                            },
                        )
                    },
                    leading = { AppIcon(R.drawable.ic_swap_horiz, null) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { editingForward = rule }) { AppIcon(R.drawable.ic_edit, "Edit forward") }
                            IconButton(onClick = { forwards = forwards - rule }) { AppIcon(R.drawable.ic_delete, "Remove forward") }
                        }
                    },
                )
            }
            TextButton(
                onClick = { editingForward = ForwardRule(id = "", kind = ForwardKind.LOCAL, bindPort = 8080, destinationHost = "localhost", destinationPort = 80) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                AppIcon(R.drawable.ic_add, null)
                Text("Add port forward", Modifier.padding(start = 8.dp))
            }
        }
    }

    editingForward?.let { rule ->
        ForwardDialog(
            initial = rule,
            onDismiss = { editingForward = null },
            onSave = { saved ->
                forwards = if (rule.id.isEmpty()) {
                    forwards + saved.copy(id = AppStore.newId())
                } else {
                    forwards.map { if (it.id == rule.id) saved else it }
                }
                editingForward = null
            },
        )
    }
}

/** Dialog for one port forward; also used to add forwards to a running session. */
@Composable
fun ForwardDialog(initial: ForwardRule, onDismiss: () -> Unit, onSave: (ForwardRule) -> Unit) {
    var kind by remember { mutableStateOf(initial.kind) }
    var bindAddress by remember { mutableStateOf(initial.bindAddress) }
    var bindPort by remember { mutableStateOf(initial.bindPort.toString()) }
    var destinationHost by remember { mutableStateOf(initial.destinationHost) }
    var destinationPort by remember { mutableStateOf(if (initial.destinationPort > 0) initial.destinationPort.toString() else "") }

    val bindPortNumber = bindPort.toIntOrNull()?.takeIf { it in 0..65535 }
    val destinationPortNumber = destinationPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val needsDestination = kind != ForwardKind.DYNAMIC
    val valid = bindPortNumber != null && bindAddress.isNotBlank() &&
        (!needsDestination || (destinationHost.isNotBlank() && destinationPortNumber != null))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Port forward") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField(
                    label = "Type",
                    selected = kind,
                    options = ForwardKind.entries,
                    optionLabel = {
                        when (it) {
                            ForwardKind.LOCAL -> "Local (-L)"
                            ForwardKind.REMOTE -> "Remote (-R)"
                            ForwardKind.DYNAMIC -> "Dynamic SOCKS proxy (-D)"
                        }
                    },
                    onSelect = { kind = it },
                )
                val listenSide = if (kind == ForwardKind.REMOTE) "server" else "this device"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        bindAddress,
                        { bindAddress = it.trim() },
                        label = { Text("Listen on ($listenSide)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        bindPort,
                        { bindPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        isError = bindPortNumber == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                }
                if (needsDestination) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            destinationHost,
                            { destinationHost = it.trim() },
                            label = { Text("Destination") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            destinationPort,
                            { destinationPort = it.filter(Char::isDigit).take(5) },
                            label = { Text("Port") },
                            singleLine = true,
                            isError = destinationPortNumber == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(96.dp),
                        )
                    }
                }
                Text(
                    when (kind) {
                        ForwardKind.LOCAL -> "Connections to the port on this device come out at the destination, as seen from the server."
                        ForwardKind.REMOTE -> "Connections to the port on the server come out at the destination, as seen from this device."
                        ForwardKind.DYNAMIC -> "Point apps at this SOCKS5 proxy to send their traffic through the server."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            kind = kind,
                            bindAddress = bindAddress,
                            bindPort = bindPortNumber ?: 0,
                            destinationHost = if (needsDestination) destinationHost else "",
                            destinationPort = if (needsDestination) destinationPortNumber ?: 0 else 0,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
