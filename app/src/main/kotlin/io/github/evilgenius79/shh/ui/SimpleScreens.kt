@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.BuildConfig
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.KnownHost
import io.github.evilgenius79.shh.data.Snippet
import io.github.evilgenius79.shh.data.fingerprint

@Composable
fun KnownHostsScreen(data: AppData, onForget: (KnownHost) -> Unit, onBack: () -> Unit) {
    var pending by remember { mutableStateOf<KnownHost?>(null) }
    Scaffold(topBar = { BackTopBar("Known hosts", onBack) }) { padding ->
        if (data.knownHosts.isEmpty()) {
            EmptyState(
                R.drawable.ic_fingerprint,
                "No known hosts",
                "Server keys you accept when connecting are remembered here, so that a changed key is noticed.",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(data.knownHosts.sortedWith(compareBy({ it.host }, { it.port }, { it.keyType }))) { entry ->
                    Entry(
                        headline = if (entry.port == 22) entry.host else "${entry.host}:${entry.port}",
                        supporting = {
                            Column {
                                Text(entry.keyType)
                                Text(entry.fingerprint(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        leading = { AppIcon(R.drawable.ic_fingerprint, null) },
                        trailing = { IconButton(onClick = { pending = entry }) { AppIcon(R.drawable.ic_delete, "Forget this key") } },
                    )
                }
            }
        }
    }
    pending?.let { entry ->
        ConfirmDialog(
            title = "Forget this key?",
            text = "The next connection to ${entry.host} will ask you to verify its key again.",
            confirmLabel = "Forget",
            destructive = true,
            onConfirm = {
                pending = null
                onForget(entry)
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
fun SnippetsScreen(snippets: List<Snippet>, onSave: (Snippet) -> Unit, onDelete: (Snippet) -> Unit, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Snippet?>(null) }
    Scaffold(
        topBar = { BackTopBar("Snippets", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Snippet(id = "", name = "", command = "") }) { AppIcon(R.drawable.ic_add, "Add snippet") }
        },
    ) { padding ->
        if (snippets.isEmpty()) {
            EmptyState(
                R.drawable.ic_code,
                "No snippets",
                "Save commands you type often and send them to a terminal from its menu.",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(snippets.sortedBy { it.name.lowercase() }, key = { it.id }) { snippet ->
                    Entry(
                        headline = snippet.name,
                        supporting = { Text(snippet.command, maxLines = 2, fontFamily = FontFamily.Monospace) },
                        leading = { AppIcon(R.drawable.ic_code, null) },
                        onClick = { editing = snippet },
                    )
                }
            }
        }
    }
    editing?.let { snippet ->
        var name by remember(snippet) { mutableStateOf(snippet.name) }
        var command by remember(snippet) { mutableStateOf(snippet.command) }
        var run by remember(snippet) { mutableStateOf(snippet.runImmediately) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (snippet.id.isEmpty()) "New snippet" else "Edit snippet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        command,
                        { command = it },
                        label = { Text("Command") },
                        minLines = 2,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow("Run immediately", "Press Enter after inserting the command", run, { run = it })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && command.isNotBlank(),
                    onClick = {
                        onSave(snippet.copy(id = snippet.id.ifEmpty { AppStore.newId() }, name = name.trim(), command = command, runImmediately = run))
                        editing = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                if (snippet.id.isNotEmpty()) {
                    TextButton(onClick = {
                        onDelete(snippet)
                        editing = null
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var license by remember { mutableStateOf<Pair<String, String>?>(null) }
    Scaffold(topBar = { BackTopBar("About", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Entry(
                headline = "Shh ${BuildConfig.VERSION_NAME}",
                supporting = { Text("An open-source SSH client. Licensed under the Apache License 2.0.") },
                leading = { AppIcon(R.drawable.ic_terminal, null) },
            )
            SectionHeader("Open-source components")
            val components = listOf(
                Triple("sshj", "SSH protocol implementation. Apache License 2.0.", null),
                Triple("Bouncy Castle", "Cryptography provider. MIT License.", null),
                Triple("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0.", null),
                Triple("AndroidX and Jetpack Compose", "Apache License 2.0.", null),
                Triple("Material Symbols", "Icons by Google. Apache License 2.0.", null),
                Triple("SLF4J", "Logging facade. MIT License.", null),
                Triple("JetBrains Mono", "Terminal font. SIL Open Font License 1.1.", "licenses/JetBrainsMono-OFL.txt"),
            )
            for ((name, summary, asset) in components) {
                Entry(
                    headline = name,
                    supporting = { Text(summary) },
                    onClick = asset?.let { path ->
                        { license = name to context.assets.open(path).bufferedReader().use { it.readText() } }
                    },
                )
            }
        }
    }
    license?.let { (name, text) ->
        AlertDialog(
            onDismissRequest = { license = null },
            title = { Text(name) },
            text = { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { license = null }) { Text("Close") } },
        )
    }
}
