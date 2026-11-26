@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.SftpEntry
import io.github.evilgenius79.shh.ssh.SftpFileType
import io.github.evilgenius79.shh.ssh.SftpSession
import io.github.evilgenius79.shh.ssh.TransferCancelledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A transfer that is under way. [total] is -1 when the size is not known. */
class TransferProgress(val name: String, val upload: Boolean, val done: Long, val total: Long)

/** State and operations of the file browser. All SFTP calls run on the IO dispatcher. */
class FilesState(private val session: TerminalSession, private val scope: CoroutineScope) {
    var path by mutableStateOf("")
        private set
    var entries by mutableStateOf<List<SftpEntry>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
    var transfer by mutableStateOf<TransferProgress?>(null)
        private set

    private var sftp: SftpSession? = null
    private val cancelled = AtomicBoolean(false)

    val canGoUp: Boolean get() = path.isNotEmpty() && path != "/"

    fun open() = run("Could not open the file browser") {
        val connection = session.connection ?: throw IOException("The session is not connected")
        val opened = connection.openSftp()
        sftp = opened
        val home = opened.homeDirectory()
        val listing = opened.list(home)
        withContext(Dispatchers.Main) {
            path = home
            entries = listing
        }
    }

    fun navigate(target: String) = run("Could not open $target") {
        val client = sftp ?: throw IOException("Not connected")
        val resolved = client.canonicalize(target)
        val listing = client.list(resolved)
        withContext(Dispatchers.Main) {
            path = resolved
            entries = listing
        }
    }

    fun up() {
        if (canGoUp) navigate(path.substringBeforeLast('/').ifEmpty { "/" })
    }

    fun refresh() = navigate(path)

    fun child(name: String): String = if (path.endsWith("/")) path + name else "$path/$name"

    fun makeDirectory(name: String) = modify("Could not create the folder") { it.makeDirectory(child(name)) }

    fun rename(entry: SftpEntry, newName: String) = modify("Could not rename ${entry.name}") { it.rename(entry.path, child(newName)) }

    fun delete(entry: SftpEntry) = modify("Could not delete ${entry.name}") { it.delete(entry.path) }

    fun setPermissions(entry: SftpEntry, mode: Int) = modify("Could not change permissions") { it.setPermissions(entry.path, mode) }

    /** Runs a change and reloads the listing afterwards. */
    private fun modify(failure: String, action: (SftpSession) -> Unit) = run(failure) {
        val client = sftp ?: throw IOException("Not connected")
        action(client)
        val listing = client.list(path)
        withContext(Dispatchers.Main) { entries = listing }
    }

    fun download(context: Context, entry: SftpEntry, destination: Uri) = run("Download of ${entry.name} failed") {
        val client = sftp ?: throw IOException("Not connected")
        cancelled.set(false)
        try {
            val out = context.contentResolver.openOutputStream(destination) ?: throw IOException("The destination could not be opened")
            out.use { stream ->
                client.download(
                    entry.path,
                    stream,
                    onProgress = { done -> transfer = TransferProgress(entry.name, false, done, entry.size) },
                    isCancelled = { cancelled.get() },
                )
            }
        } finally {
            transfer = null
        }
    }

    fun upload(context: Context, source: Uri) {
        val (name, size) = describe(context, source)
        run("Upload of $name failed") {
            val client = sftp ?: throw IOException("Not connected")
            cancelled.set(false)
            try {
                val input = context.contentResolver.openInputStream(source) ?: throw IOException("The file could not be opened")
                input.use { stream ->
                    client.upload(
                        stream,
                        child(name),
                        onProgress = { done -> transfer = TransferProgress(name, true, done, size) },
                        isCancelled = { cancelled.get() },
                    )
                }
                val listing = client.list(path)
                withContext(Dispatchers.Main) { entries = listing }
            } finally {
                transfer = null
            }
        }
    }

    fun cancelTransfer() = cancelled.set(true)

    /** Reads a file for the text editor; reports through [onResult] on the main thread. */
    fun readText(entry: SftpEntry, onResult: (String) -> Unit) = run("Could not open ${entry.name}") {
        val client = sftp ?: throw IOException("Not connected")
        val text = client.readText(entry.path)
        withContext(Dispatchers.Main) { onResult(text) }
    }

    fun writeText(entry: SftpEntry, text: String, onSaved: () -> Unit) = run("Could not save ${entry.name}") {
        val client = sftp ?: throw IOException("Not connected")
        client.writeText(entry.path, text)
        val listing = client.list(path)
        withContext(Dispatchers.Main) {
            entries = listing
            onSaved()
        }
    }

    private fun describe(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                if (!cursor.isNull(0)) name = cursor.getString(0)
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        return name.replace('/', '_') to size
    }

    private fun run(failure: String, block: suspend () -> Unit) {
        loading = true
        scope.launch(Dispatchers.IO) {
            try {
                block()
                withContext(Dispatchers.Main) { error = null }
            } catch (_: TransferCancelledException) {
                withContext(Dispatchers.Main) { error = "Transfer cancelled" }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) { error = "$failure: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    fun close() {
        val client = sftp
        sftp = null
        if (client != null) Thread({ client.close() }, "shh-sftp-close").apply { isDaemon = true }.start()
    }
}

@Composable
fun FilesScreen(session: TerminalSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(session) { FilesState(session, scope) }
    DisposableEffect(state) {
        state.open()
        onDispose { state.close() }
    }

    var selected by remember { mutableStateOf<SftpEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SftpEntry?>(null) }
    var deleting by remember { mutableStateOf<SftpEntry?>(null) }
    var permissions by remember { mutableStateOf<SftpEntry?>(null) }
    var editing by remember { mutableStateOf<Pair<SftpEntry, String>?>(null) }
    var downloadTarget by remember { mutableStateOf<SftpEntry?>(null) }

    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.upload(context, uri)
    }
    val downloader = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = downloadTarget
        downloadTarget = null
        if (uri != null && entry != null) state.download(context, entry, uri)
    }

    // Back first walks up the directory tree, like a file manager.
    BackHandler(enabled = state.canGoUp && editing == null) { state.up() }

    Scaffold(
        topBar = {
            BackTopBar(session.host.title, onBack) {
                IconButton(onClick = { uploader.launch(arrayOf("*/*")) }) { AppIcon(R.drawable.ic_upload, "Upload a file") }
                IconButton(onClick = { newFolder = true }) { AppIcon(R.drawable.ic_create_new_folder, "New folder") }
                IconButton(onClick = { state.refresh() }) { AppIcon(R.drawable.ic_refresh, "Refresh") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { state.up() }, enabled = state.canGoUp) { AppIcon(R.drawable.ic_arrow_upward, "Parent folder") }
                    Text(
                        state.path.ifEmpty { "…" },
                        maxLines = 1,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.loading) CircularProgressIndicator(Modifier.padding(12.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                }
            }
            state.transfer?.let { transfer ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (transfer.upload) "Uploading " else "Downloading ") + transfer.name + " · " +
                                Formatter.formatShortFileSize(context, transfer.done),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { state.cancelTransfer() }) { Text("Cancel") }
                    }
                    if (transfer.total > 0) {
                        LinearProgressIndicator(
                            progress = { (transfer.done.toFloat() / transfer.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            state.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.entries.isEmpty() && !state.loading && state.error == null) {
                EmptyState(R.drawable.ic_folder, "Empty folder", "Upload a file or create a folder with the buttons above.")
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.entries, key = { it.path }) { entry ->
                    Entry(
                        headline = entry.name,
                        supporting = {
                            val size = if (entry.type == SftpFileType.FILE) Formatter.formatShortFileSize(context, entry.size) + " · " else ""
                            val date = DateUtils.formatDateTime(
                                context,
                                entry.modifiedSeconds * 1000,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                            )
                            Text("$size$date · ${entry.permissionString}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        },
                        leading = {
                            AppIcon(
                                when {
                                    entry.isSymlink -> R.drawable.ic_link
                                    entry.isDirectory -> R.drawable.ic_folder
                                    else -> R.drawable.ic_description
                                },
                                null,
                                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailing = {
                            Box {
                                IconButton(onClick = { selected = entry }) { AppIcon(R.drawable.ic_more_vert, "Options for ${entry.name}") }
                                DropdownMenu(expanded = selected == entry, onDismissRequest = { selected = null }) {
                                    @Composable
                                    fun action(label: String, icon: Int, run: () -> Unit) {
                                        DropdownMenuItem(text = { Text(label) }, leadingIcon = { AppIcon(icon, null) }, onClick = {
                                            selected = null
                                            run()
                                        })
                                    }
                                    if (!entry.isDirectory) {
                                        action("Download", R.drawable.ic_download) {
                                            downloadTarget = entry
                                            downloader.launch(entry.name)
                                        }
                                        action("Edit as text", R.drawable.ic_edit) { state.readText(entry) { text -> editing = entry to text } }
                                    }
                                    action("Rename", R.drawable.ic_drive_file_rename_outline) { renaming = entry }
                                    action("Permissions", R.drawable.ic_lock) { permissions = entry }
                                    action("Copy path", R.drawable.ic_content_copy) { copyToClipboard(context, "Path", entry.path) }
                                    action("Delete", R.drawable.ic_delete) { deleting = entry }
                                }
                            }
                        },
                        onClick = {
                            if (entry.isDirectory) state.navigate(entry.path) else selected = entry
                        },
                    )
                }
            }
        }
    }

    if (newFolder) {
        NameDialog("New folder", "Name", "", "Create", onDismiss = { newFolder = false }) { name ->
            newFolder = false
            state.makeDirectory(name)
        }
    }
    renaming?.let { entry ->
        NameDialog("Rename", "New name", entry.name, "Rename", onDismiss = { renaming = null }) { name ->
            renaming = null
            state.rename(entry, name)
        }
    }
    deleting?.let { entry ->
        ConfirmDialog(
            title = "Delete ${entry.name}?",
            text = if (entry.type == SftpFileType.DIRECTORY) "The folder and everything in it is deleted on the server. This cannot be undone."
            else "The file is deleted on the server. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                deleting = null
                state.delete(entry)
            },
            onDismiss = { deleting = null },
        )
    }
    permissions?.let { entry ->
        var octal by remember(entry) { mutableStateOf(Integer.toOctalString(entry.permissions and 0xFFF).padStart(3, '0')) }
        val mode = octal.toIntOrNull(8)?.takeIf { it in 0..0xFFF && octal.length in 3..4 }
        AlertDialog(
            onDismissRequest = { permissions = null },
            title = { Text("Permissions of ${entry.name}") },
            text = {
                OutlinedTextField(
                    octal,
                    { octal = it.filter { c -> c in '0'..'7' }.take(4) },
                    label = { Text("Octal mode, for example 644") },
                    singleLine = true,
                    isError = mode == null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
            },
            confirmButton = {
                TextButton(enabled = mode != null, onClick = {
                    permissions = null
                    mode?.let { state.setPermissions(entry, it) }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { permissions = null }) { Text("Cancel") } },
        )
    }
    editing?.let { (entry, original) ->
        TextEditor(
            title = entry.name,
            original = original,
            onClose = { editing = null },
            onSave = { text -> state.writeText(entry, text) { editing = null } },
        )
    }
}

@Composable
private fun NameDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && '/' !in name && name != "." && name != ".."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(label) }, singleLine = true, isError = !valid && name.isNotEmpty()) },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(name.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A full screen plain text editor for small remote files. */
@Composable
private fun TextEditor(title: String, original: String, onClose: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(original) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val changed = text != original
    BackHandler { if (changed) confirmDiscard = true else onClose() }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                BackTopBar(title, onBack = { if (changed) confirmDiscard = true else onClose() }) {
                    TextButton(enabled = changed, onClick = { onSave(text) }) { Text("Save") }
                }
            },
        ) { padding ->
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(8.dp),
            )
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "Your edits to $title have not been saved.",
            confirmLabel = "Discard",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onClose()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}
