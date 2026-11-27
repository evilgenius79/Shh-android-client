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
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.ForwardRule
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.InternetProtocol
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
    val passwordState = rememberTextFieldState()
    val password = passwordState.text.toString()
    var removePassword by rememberSaveable { mutableStateOf(false) }
    var identityId by rememberSaveable { mutableStateOf(original.identityId) }
    var jumpHostId by rememberSaveable { mutableStateOf(original.jumpHostId) }
    var startupCommand by rememberSaveable { mutableStateOf(original.startupCommand) }
    var colorSchemeId by rememberSaveable { mutableStateOf(original.colorSchemeId) }
    var legacy by rememberSaveable { mutableStateOf(original.allowLegacyAlgorithms) }
    var compression by rememberSaveable { mutableStateOf(original.compression) }
    var keepAlive by rememberSaveable { mutableStateOf(original.keepAliveSeconds.toString()) }
    var internetProtocol by rememberSaveable { mutableStateOf(original.internetProtocol) }
    var forwardAgent by rememberSaveable { mutableStateOf(original.forwardAgent) }
    var forwards by remember { mutableStateOf(original.forwards) }
    var editingForward by remember { mutableStateOf<ForwardRule?>(null) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    // For the choices of the fields, whose texts are made outside composition.
    val resources = LocalResources.current

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
            internetProtocol = internetProtocol,
            forwardAgent = forwardAgent,
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
            BackTopBar(stringResource(if (isNew) R.string.hostedit_title_new else R.string.hostedit_title_edit), onBack) {
                TextButton(onClick = ::save) { Text(stringResource(R.string.action_save)) }
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

            SectionHeader(stringResource(R.string.hostedit_section_server))
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.hostedit_name)) }, singleLine = true, modifier = field)
            Row(field, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    address,
                    { address = it },
                    label = { Text(stringResource(R.string.hostedit_address)) },
                    singleLine = true,
                    isError = showErrors && !addressValid,
                    supportingText = if (showErrors && !addressValid) ({ Text(stringResource(R.string.hostedit_address_error)) }) else null,
                    keyboardOptions = plainText.copy(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.label_port)) },
                    singleLine = true,
                    isError = showErrors && portNumber == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            OutlinedTextField(
                username,
                { username = it },
                label = { Text(stringResource(R.string.label_user_name)) },
                singleLine = true,
                isError = showErrors && username.isBlank(),
                supportingText = if (showErrors && username.isBlank()) ({ Text(stringResource(R.string.hostedit_required)) }) else null,
                keyboardOptions = plainText,
                modifier = field,
            )
            OutlinedTextField(group, { group = it }, label = { Text(stringResource(R.string.hostedit_group)) }, singleLine = true, modifier = field)

            SectionHeader(stringResource(R.string.hostedit_section_authentication))
            SelectField(
                label = stringResource(R.string.hostedit_key),
                selected = data.identity(identityId),
                options = listOf<Identity?>(null) + data.identities,
                optionLabel = {
                    it?.let { key -> resources.getString(R.string.hostedit_key_option, key.name, key.algorithm) } ?: resources.getString(R.string.hostedit_key_none)
                },
                onSelect = { identityId = it?.id },
                modifier = field,
            )
            SecretField(
                state = passwordState,
                label = stringResource(if (hasSavedPassword && !removePassword) R.string.hostedit_password_saved else R.string.hostedit_password),
                supportingText = stringResource(R.string.hostedit_password_hint),
                modifier = field,
            )
            if (hasSavedPassword && !removePassword && password.isEmpty()) {
                TextButton(onClick = { removePassword = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.hostedit_forget_password))
                }
            }

            SectionHeader(stringResource(R.string.hostedit_section_connection))
            SelectField(
                label = stringResource(R.string.hostedit_jump_host),
                selected = data.host(jumpHostId),
                options = listOf<Host?>(null) + data.hosts.filter { it.id != original.id },
                optionLabel = { it?.title ?: resources.getString(R.string.hostedit_jump_host_none) },
                onSelect = { jumpHostId = it?.id },
                modifier = field,
            )
            OutlinedTextField(
                keepAlive,
                { keepAlive = it.filter(Char::isDigit).take(4) },
                label = { Text(stringResource(R.string.hostedit_keep_alive)) },
                singleLine = true,
                isError = showErrors && keepAliveNumber == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = field,
            )
            SelectField(
                label = stringResource(R.string.hostedit_internet_protocol),
                selected = internetProtocol,
                options = InternetProtocol.entries,
                optionLabel = {
                    resources.getString(
                        when (it) {
                            InternetProtocol.AUTOMATIC -> R.string.hostedit_internet_protocol_automatic
                            InternetProtocol.IPV4 -> R.string.hostedit_internet_protocol_ipv4
                            InternetProtocol.IPV6 -> R.string.hostedit_internet_protocol_ipv6
                        },
                    )
                },
                onSelect = { internetProtocol = it },
                modifier = field,
            )
            SwitchRow(
                stringResource(R.string.hostedit_forward_agent),
                stringResource(R.string.hostedit_forward_agent_summary),
                forwardAgent,
                { forwardAgent = it },
            )
            SwitchRow(stringResource(R.string.hostedit_compression), stringResource(R.string.hostedit_compression_summary), compression, { compression = it })
            SwitchRow(
                stringResource(R.string.hostedit_legacy_algorithms),
                stringResource(R.string.hostedit_legacy_algorithms_summary),
                legacy,
                { legacy = it },
            )

            SectionHeader(stringResource(R.string.hostedit_section_terminal))
            OutlinedTextField(
                startupCommand,
                { startupCommand = it },
                label = { Text(stringResource(R.string.hostedit_startup_command)) },
                placeholder = { Text("tmux new -A -s main") },
                singleLine = true,
                keyboardOptions = plainText,
                modifier = field,
            )
            SelectField(
                label = stringResource(R.string.hostedit_colors),
                selected = colorSchemeId,
                options = listOf<String?>(null) + ColorScheme.ALL.map { it.id },
                optionLabel = { id -> if (id == null) resources.getString(R.string.hostedit_colors_default) else ColorScheme.byId(id).name },
                onSelect = { colorSchemeId = it },
                modifier = field,
            )

            SectionHeader(stringResource(R.string.hostedit_section_forwards))
            for (rule in forwards) {
                Entry(
                    headline = rule.summary,
                    supporting = {
                        Text(
                            stringResource(
                                when (rule.kind) {
                                    ForwardKind.LOCAL -> R.string.hostedit_forward_local_summary
                                    ForwardKind.REMOTE -> R.string.hostedit_forward_remote_summary
                                    ForwardKind.DYNAMIC -> R.string.hostedit_forward_dynamic_summary
                                },
                            ),
                        )
                    },
                    leading = { AppIcon(R.drawable.ic_swap_horiz, null) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { editingForward = rule }) { AppIcon(R.drawable.ic_edit, stringResource(R.string.hostedit_forward_edit)) }
                            IconButton(onClick = { forwards = forwards - rule }) {
                                AppIcon(R.drawable.ic_delete, stringResource(R.string.hostedit_forward_remove))
                            }
                        }
                    },
                )
            }
            TextButton(
                onClick = { editingForward = ForwardRule(id = "", kind = ForwardKind.LOCAL, bindPort = 8080, destinationHost = "localhost", destinationPort = 80) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                AppIcon(R.drawable.ic_add, null)
                Text(stringResource(R.string.hostedit_forward_add), Modifier.padding(start = 8.dp))
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
    val resources = LocalResources.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hostedit_forward_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField(
                    label = stringResource(R.string.hostedit_forward_type),
                    selected = kind,
                    options = ForwardKind.entries,
                    optionLabel = {
                        resources.getString(
                            when (it) {
                                ForwardKind.LOCAL -> R.string.hostedit_forward_type_local
                                ForwardKind.REMOTE -> R.string.hostedit_forward_type_remote
                                ForwardKind.DYNAMIC -> R.string.hostedit_forward_type_dynamic
                            },
                        )
                    },
                    onSelect = { kind = it },
                )
                // A remote forward listens on the server, the other kinds on this device.
                val listenOn = if (kind == ForwardKind.REMOTE) R.string.hostedit_forward_listen_on_server else R.string.hostedit_forward_listen_on_device
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        bindAddress,
                        { bindAddress = it.trim() },
                        label = { Text(stringResource(listenOn)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        bindPort,
                        { bindPort = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.label_port)) },
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
                            label = { Text(stringResource(R.string.hostedit_forward_destination)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            destinationPort,
                            { destinationPort = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.label_port)) },
                            singleLine = true,
                            isError = destinationPortNumber == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(96.dp),
                        )
                    }
                }
                Text(
                    stringResource(
                        when (kind) {
                            ForwardKind.LOCAL -> R.string.hostedit_forward_local_explanation
                            ForwardKind.REMOTE -> R.string.hostedit_forward_remote_explanation
                            ForwardKind.DYNAMIC -> R.string.hostedit_forward_dynamic_explanation
                        },
                    ),
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
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
