@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.evilgenius79.shh.AppContainer
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.Identity
import io.github.evilgenius79.shh.data.SecretBox
import io.github.evilgenius79.shh.ssh.KeyAlgorithm
import io.github.evilgenius79.shh.ssh.LoadedKey
import io.github.evilgenius79.shh.ssh.PassphraseRequiredException
import io.github.evilgenius79.shh.ssh.SshKeys
import io.github.evilgenius79.shh.ssh.WrongPassphraseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Wraps a key pair for storage: the private half is written in OpenSSH format and encrypted. */
fun identityFrom(key: LoadedKey, name: String, secrets: SecretBox): Identity = Identity(
    id = AppStore.newId(),
    name = name,
    algorithm = key.description,
    publicKey = key.publicKey.toAuthorizedKey(),
    fingerprint = key.publicKey.fingerprint,
    encryptedPrivateKey = secrets.encryptText(SshKeys.toOpenSshPrivateKey(key, name)),
    createdAt = System.currentTimeMillis(),
)

fun copyToClipboard(context: Context, label: String, text: String) {
    ContextCompat.getSystemService(context, ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
fun KeysScreen(container: AppContainer, data: AppData, onBack: () -> Unit) {
    var fabMenu by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Identity?>(null) }

    Scaffold(
        topBar = { BackTopBar("Keys", onBack) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) { AppIcon(R.drawable.ic_add, "Add key") }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(text = { Text("Generate a new key") }, leadingIcon = { AppIcon(R.drawable.ic_key, null) }, onClick = {
                        fabMenu = false
                        generating = true
                    })
                    DropdownMenuItem(text = { Text("Import an existing key") }, leadingIcon = { AppIcon(R.drawable.ic_upload, null) }, onClick = {
                        fabMenu = false
                        importing = true
                    })
                }
            }
        },
    ) { padding ->
        if (data.identities.isEmpty()) {
            EmptyState(
                R.drawable.ic_key,
                "No keys yet",
                "Generate a key pair or import one, then put its public key on your servers to log in without a password.",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(data.identities.sortedBy { it.name.lowercase() }, key = { it.id }) { identity ->
                    val users = data.hosts.count { it.identityId == identity.id }
                    Entry(
                        headline = identity.name,
                        supporting = {
                            Column {
                                Text(identity.algorithm + if (users > 0) " · used by $users host" + (if (users > 1) "s" else "") else "")
                                Text(identity.fingerprint, maxLines = 1, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        leading = { AppIcon(R.drawable.ic_key, null) },
                        onClick = { selected = identity },
                    )
                }
            }
        }
    }

    if (generating) GenerateKeyDialog(container, onDone = { generating = false })
    if (importing) ImportKeyDialog(container, onDone = { importing = false })
    selected?.let { identity ->
        KeyDetailsDialog(
            container = container,
            identity = identity,
            usedBy = data.hosts.count { it.identityId == identity.id },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun GenerateKeyDialog(container: AppContainer, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf(KeyAlgorithm.ED25519) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onDone() },
        title = { Text("Generate key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth())
                SelectField("Type", algorithm, KeyAlgorithm.entries, { it.displayName }, { algorithm = it })
                Text(
                    if (algorithm == KeyAlgorithm.ED25519) "Ed25519 is the recommended choice: small, fast and supported by every current server."
                    else "Use this only if a server does not accept Ed25519 keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Generating…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !working,
                onClick = {
                    working = true
                    scope.launch {
                        val identity = withContext(Dispatchers.Default) {
                            identityFrom(SshKeys.generate(algorithm), name.trim(), container.secrets)
                        }
                        container.store.saveIdentity(identity)
                        onDone()
                    }
                },
            ) { Text("Generate") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun ImportKeyDialog(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var needsPassphrase by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.size > MAX_KEY_FILE_BYTES) {
                        error = "That file is too large to be a private key."
                    } else {
                        text = String(bytes, Charsets.UTF_8)
                        error = null
                    }
                }
            } catch (e: IOException) {
                error = "The file could not be read: ${e.message}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Import key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    text,
                    {
                        text = it
                        needsPassphrase = false
                        error = null
                    },
                    label = { Text("Private key") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { picker.launch("*/*") }) { Text("Choose a file…") }
                if (needsPassphrase) SecretField(passphrase, { passphrase = it }, "Passphrase of this key", Modifier.fillMaxWidth())
                Text(
                    "OpenSSH, PEM, PKCS#8 and PuTTY (.ppk) keys are supported. The key is stored encrypted on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && text.isNotBlank(),
                onClick = {
                    try {
                        val key = SshKeys.load(text, passphrase.takeIf { needsPassphrase }?.toCharArray())
                        container.store.saveIdentity(identityFrom(key, name.trim(), container.secrets))
                        onDone()
                    } catch (_: PassphraseRequiredException) {
                        needsPassphrase = true
                        error = "This key is protected by a passphrase."
                    } catch (_: WrongPassphraseException) {
                        needsPassphrase = true
                        error = "That passphrase is not correct."
                    } catch (e: IOException) {
                        error = e.message ?: "This is not a private key that can be read."
                    }
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun KeyDetailsDialog(container: AppContainer, identity: Identity, usedBy: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val publicLine = "${identity.publicKey} ${identity.name}"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(identity.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(identity.algorithm, style = MaterialTheme.typography.labelLarge)
                Text(identity.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Public key", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(publicLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Add the public key as a line to ~/.ssh/authorized_keys on a server to log in with this key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { copyToClipboard(context, "Public key", publicLine) }) { Text("Copy") }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, publicLine)
                        context.startActivity(Intent.createChooser(send, "Share public key"))
                    }) { Text("Share") }
                    TextButton(onClick = { exporting = true }) { Text("Export private key") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { deleting = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
    )

    if (deleting) {
        ConfirmDialog(
            title = "Delete ${identity.name}?",
            text = if (usedBy > 0) "$usedBy host(s) use this key and will fall back to password login. This cannot be undone."
            else "The private key is removed from this device. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                container.store.deleteIdentity(identity.id)
                deleting = false
                onDismiss()
            },
            onDismiss = { deleting = false },
        )
    }
    if (exporting) ExportKeyDialog(container, identity, onDone = { exporting = false })
}

@Composable
private fun ExportKeyDialog(container: AppContainer, identity: Identity, onDone: () -> Unit) {
    val context = LocalContext.current
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val key = SshKeys.load(container.secrets.decryptText(identity.encryptedPrivateKey))
            val text = SshKeys.toOpenSshPrivateKey(key, identity.name, passphrase.takeIf { it.isNotEmpty() }?.toCharArray())
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IOException("The destination could not be opened")
            onDone()
        } catch (e: Exception) {
            error = "Export failed: ${e.message}"
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Export private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Writes the private key in OpenSSH format, as used by ssh and ssh-add. Anyone who gets this file can log in as you.")
                SecretField(
                    passphrase,
                    { passphrase = it },
                    "Passphrase (recommended)",
                    Modifier.fillMaxWidth(),
                    supportingText = "Leave empty to write the key unencrypted.",
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { saver.launch(identity.name.replace(Regex("[^A-Za-z0-9._-]"), "_")) }) { Text("Choose where to save") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

private const val MAX_KEY_FILE_BYTES = 64 * 1024
