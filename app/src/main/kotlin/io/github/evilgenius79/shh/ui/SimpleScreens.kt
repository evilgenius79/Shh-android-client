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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
    Scaffold(topBar = { BackTopBar(stringResource(R.string.knownhosts_title), onBack) }) { padding ->
        if (data.knownHosts.isEmpty()) {
            EmptyState(
                R.drawable.ic_fingerprint,
                stringResource(R.string.knownhosts_empty_title),
                stringResource(R.string.knownhosts_empty_text),
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
                        trailing = {
                            IconButton(onClick = { pending = entry }) { AppIcon(R.drawable.ic_delete, stringResource(R.string.knownhosts_forget)) }
                        },
                    )
                }
            }
        }
    }
    pending?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.knownhosts_forget_title),
            text = stringResource(R.string.knownhosts_forget_text, entry.host),
            confirmLabel = stringResource(R.string.knownhosts_forget_action),
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
        topBar = { BackTopBar(stringResource(R.string.snippets_title), onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Snippet(id = "", name = "", command = "") }) {
                AppIcon(R.drawable.ic_add, stringResource(R.string.snippets_add))
            }
        },
    ) { padding ->
        if (snippets.isEmpty()) {
            EmptyState(
                R.drawable.ic_code,
                stringResource(R.string.snippets_empty_title),
                stringResource(R.string.snippets_empty_text),
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(snippets.sortedBy { it.name.lowercase() }, key = { it.id }) { snippet ->
                    Entry(
                        headline = snippet.name,
                        supporting = { Text(snippet.command, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) },
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
            title = { Text(stringResource(if (snippet.id.isEmpty()) R.string.snippets_new_title else R.string.snippets_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text(stringResource(R.string.label_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        command,
                        { command = it },
                        label = { Text(stringResource(R.string.snippets_command)) },
                        minLines = 2,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow(stringResource(R.string.snippets_run_immediately), stringResource(R.string.snippets_run_immediately_summary), run, { run = it })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && command.isNotBlank(),
                    onClick = {
                        onSave(snippet.copy(id = snippet.id.ifEmpty { AppStore.newId() }, name = name.trim(), command = command, runImmediately = run))
                        editing = null
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                if (snippet.id.isNotEmpty()) {
                    TextButton(onClick = {
                        onDelete(snippet)
                        editing = null
                    }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var license by remember { mutableStateOf<Pair<String, String>?>(null) }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.about_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Entry(
                headline = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                supporting = { Text(stringResource(R.string.about_summary)) },
                leading = { AppIcon(R.drawable.ic_terminal, null) },
            )
            SectionHeader(stringResource(R.string.about_section_components))
            // The names of software are the same in every language; only the "and" between two of them is not.
            val components = listOf(
                Triple("sshj", stringResource(R.string.about_sshj_summary), null),
                Triple("Bouncy Castle", stringResource(R.string.about_bouncy_castle_summary), null),
                Triple("Kotlin, kotlinx.coroutines, kotlinx.serialization", stringResource(R.string.about_license_apache), null),
                Triple(stringResource(R.string.about_androidx_name), stringResource(R.string.about_license_apache), null),
                Triple("Material Symbols", stringResource(R.string.about_material_symbols_summary), null),
                Triple("SLF4J", stringResource(R.string.about_slf4j_summary), null),
                Triple("JetBrains Mono", stringResource(R.string.about_jetbrains_mono_summary), "licenses/JetBrainsMono-OFL.txt"),
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
            confirmButton = { TextButton(onClick = { license = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
