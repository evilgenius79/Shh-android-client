@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.session.SessionState
import io.github.evilgenius79.shh.session.TerminalSession

/**
 * Parses what people type on an ssh command line: `user@host`, `host:2222`, `user@host:port`
 * and IPv6 literals in brackets such as `root@[2001:db8::1]:22`. Returns null for anything else.
 */
fun parseQuickConnect(text: String): Host? {
    val trimmed = text.trim().removePrefix("ssh://").removePrefix("ssh ").trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val at = trimmed.lastIndexOf('@')
    val user = if (at >= 0) trimmed.substring(0, at) else ""
    var rest = if (at >= 0) trimmed.substring(at + 1) else trimmed
    var port = 22
    val host: String
    if (rest.startsWith("[")) {
        val close = rest.indexOf(']')
        if (close < 0) return null
        host = rest.substring(1, close)
        rest = rest.substring(close + 1)
        if (rest.startsWith(":")) port = rest.substring(1).toIntOrNull() ?: return null else if (rest.isNotEmpty()) return null
    } else {
        val colon = rest.lastIndexOf(':')
        // More than one colon without brackets is a bare IPv6 address, not host:port.
        if (colon >= 0 && rest.indexOf(':') == colon) {
            host = rest.substring(0, colon)
            port = rest.substring(colon + 1).toIntOrNull() ?: return null
        } else {
            host = rest
        }
    }
    if (host.isEmpty() || port !in 1..65535) return null
    return Host(id = "", host = host, port = port, username = user)
}

@Composable
fun HostsScreen(
    data: AppData,
    sessions: List<TerminalSession>,
    onConnect: (Host) -> Unit,
    onQuickConnect: (Host) -> Unit,
    onOpenSession: (TerminalSession) -> Unit,
    onEdit: (Host) -> Unit,
    onDuplicate: (Host) -> Unit,
    onDelete: (Host) -> Unit,
    onAdd: () -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Host?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shh") },
                actions = {
                    IconButton(onClick = { onNavigate(Screen.Keys) }) { AppIcon(R.drawable.ic_key, "Keys") }
                    IconButton(onClick = { menuOpen = true }) { AppIcon(R.drawable.ic_more_vert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val entries = listOf(
                            Triple("Snippets", R.drawable.ic_code, Screen.Snippets),
                            Triple("Known hosts", R.drawable.ic_fingerprint, Screen.KnownHosts),
                            Triple("Settings", R.drawable.ic_settings, Screen.Settings),
                            Triple("About", R.drawable.ic_info, Screen.About),
                        )
                        for ((label, icon, screen) in entries) {
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = { AppIcon(icon, null) },
                                onClick = {
                                    menuOpen = false
                                    onNavigate(screen)
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { AppIcon(R.drawable.ic_add, null) },
                text = { Text("Add host") },
            )
        },
    ) { padding ->
        val quick = remember(query) { parseQuickConnect(query) }
        val filtered = remember(data.hosts, query) {
            val needle = query.trim()
            data.hosts
                .filter { needle.isEmpty() || it.title.contains(needle, true) || it.address.contains(needle, true) || it.group.contains(needle, true) }
                // Named groups first, in alphabetical order; hosts without a group come last.
                .sortedWith(compareBy<Host>({ it.group.isBlank() }, { it.group.lowercase() }, { it.title.lowercase() }))
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search or connect to user@host") },
                    leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { AppIcon(R.drawable.ic_close, "Clear") }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { quick?.let(onQuickConnect) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (quick != null && filtered.none { it.address == quick.address }) {
                item {
                    Entry(
                        headline = "Connect to ${quick.address}",
                        supporting = { Text("Without saving it as a host") },
                        leading = { AppIcon(R.drawable.ic_bolt, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onQuickConnect(quick) },
                    )
                }
            }
            if (sessions.isNotEmpty()) {
                item {
                    SectionHeader("Open sessions")
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (session in sessions) {
                            val state by session.state.collectAsStateCompat()
                            AssistChip(
                                onClick = { onOpenSession(session) },
                                label = { Text(session.host.title, maxLines = 1) },
                                leadingIcon = {
                                    AppIcon(
                                        R.drawable.ic_terminal,
                                        null,
                                        tint = if (state is SessionState.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            if (data.hosts.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxHeight(0.6f)) {
                        EmptyState(
                            R.drawable.ic_dns,
                            "No hosts yet",
                            "Add a server, or type user@host above to connect straight away.",
                        )
                    }
                }
            }
            var lastGroup: String? = null
            for (host in filtered) {
                if (host.group != lastGroup) {
                    lastGroup = host.group
                    val header = host.group.ifBlank { if (filtered.any { it.group.isNotBlank() }) "Ungrouped" else "Hosts" }
                    item(key = "group-${host.group}") { SectionHeader(header) }
                }
                item(key = host.id) {
                    HostRow(
                        host = host,
                        jump = data.host(host.jumpHostId),
                        onConnect = { onConnect(host) },
                        onEdit = { onEdit(host) },
                        onDuplicate = { onDuplicate(host) },
                        onDelete = { pendingDelete = host },
                    )
                }
            }
        }
    }

    pendingDelete?.let { host ->
        ConfirmDialog(
            title = "Delete ${host.title}?",
            text = "The host and its saved password are removed. Keys and known host keys are kept.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDelete(host)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun HostRow(host: Host, jump: Host?, onConnect: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Entry(
        headline = host.title,
        supporting = {
            Column {
                if (host.name.isNotBlank()) Text(host.address, maxLines = 1)
                if (jump != null) Text("via ${jump.title}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
        },
        leading = { AppIcon(R.drawable.ic_dns, null) },
        trailing = {
            Box {
                IconButton(onClick = { menuOpen = true }) { AppIcon(R.drawable.ic_more_vert, "Options for ${host.title}") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { AppIcon(R.drawable.ic_edit, null) }, onClick = {
                        menuOpen = false
                        onEdit()
                    })
                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { AppIcon(R.drawable.ic_content_copy, null) }, onClick = {
                        menuOpen = false
                        onDuplicate()
                    })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { AppIcon(R.drawable.ic_delete, null) }, onClick = {
                        menuOpen = false
                        onDelete()
                    })
                }
            }
        },
        onClick = onConnect,
    )
}
