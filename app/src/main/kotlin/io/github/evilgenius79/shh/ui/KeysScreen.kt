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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.evilgenius79.shh.ssh.SshCertificate
import io.github.evilgenius79.shh.ssh.SshKeys
import io.github.evilgenius79.shh.ssh.WrongPassphraseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date

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
                                Text(identity.fingerprint, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
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
    // Looked up afresh, so that the dialog shows a certificate as soon as it is added.
    selected?.let { data.identity(it.id) }?.let { identity ->
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
    val passphrase = rememberTextFieldState()
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
                if (needsPassphrase) SecretField(passphrase, "Passphrase of this key", Modifier.fillMaxWidth())
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
                        val key = SshKeys.load(text, passphrase.text.toString().takeIf { needsPassphrase }?.toCharArray())
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

/**
 * The certificate in [text], provided it is one for logging in with [identity]'s key.
 *
 * @throws IOException saying what is wrong with it otherwise.
 */
@Throws(IOException::class)
fun certificateFor(identity: Identity, text: String): SshCertificate {
    val certificate = SshCertificate.parse(text)
    if (certificate.publicKey.toAuthorizedKey() != identity.publicKey) throw IOException("This certificate was issued for a different key")
    if (certificate.isHostCertificate) throw IOException("This certificate identifies a server; it cannot be used to log in")
    return certificate
}

/** One line about a certificate: what it is called, whom it is for and how long it lasts. */
fun describeCertificate(certificate: SshCertificate, now: Date = Date()): String {
    val accounts = if (certificate.principals.isEmpty()) "any account" else certificate.principals.joinToString(", ")
    // Certificates without an end carry the largest possible date.
    val endless = certificate.validBefore.time < 0 || certificate.validBefore.time > FAR_FUTURE_MS
    val until = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(certificate.validBefore)
    val validity = when {
        now.before(certificate.validAfter) -> "not valid yet"
        endless -> "does not expire"
        certificate.isValidAt(now) -> "valid until $until"
        else -> "expired on $until"
    }
    return "\"${certificate.keyId}\" for $accounts, $validity"
}

private const val FAR_FUTURE_MS = 7_000_000_000_000L // the year 2191

@Composable
private fun KeyDetailsDialog(container: AppContainer, identity: Identity, usedBy: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var addingCertificate by remember { mutableStateOf(false) }
    val certificate = remember(identity.certificate) { identity.certificate?.let { runCatching { SshCertificate.parse(it) }.getOrNull() } }
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
                Text("Certificate", style = MaterialTheme.typography.labelLarge)
                if (certificate != null) {
                    Text(describeCertificate(certificate), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { container.store.saveIdentity(identity.copy(certificate = null)) }) { Text("Remove certificate") }
                } else {
                    Text(
                        "If a certificate authority signed this key, add the certificate it gave you. " +
                            "Servers that trust the authority then accept the key without knowing it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { addingCertificate = true }) { Text("Add a certificate…") }
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
    if (addingCertificate) AddCertificateDialog(container, identity, onDone = { addingCertificate = false })
}

@Composable
private fun AddCertificateDialog(container: AppContainer, identity: Identity, onDone: () -> Unit) {
    val context = LocalContext.current
    val text = rememberTextFieldState()
    var error by remember { mutableStateOf<String?>(null) }
    // A complaint about the text is out of date as soon as the text changes.
    LaunchedEffect(text.text) { error = null }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            // A certificate is a single line; anything large is the wrong file.
            val content = context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray().decodeToString() }.orEmpty()
            text.setTextAndPlaceCursorAtEnd(content)
        } catch (e: IOException) {
            error = "The file could not be read: ${e.message}"
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add a certificate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste the contents of the -cert.pub file made for this key, or choose the file.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    state = text,
                    label = { Text("Certificate") },
                    placeholder = { Text("ssh-ed25519-cert-v01@openssh.com AAAA…") },
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 6),
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { picker.launch("*/*") }) { Text("Choose a file…") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.text.isNotBlank(),
                onClick = {
                    try {
                        val certificate = certificateFor(identity, text.text.toString())
                        container.store.saveIdentity(identity.copy(certificate = certificate.toLine()))
                        onDone()
                    } catch (e: IOException) {
                        error = e.message
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun ExportKeyDialog(container: AppContainer, identity: Identity, onDone: () -> Unit) {
    val context = LocalContext.current
    val passphrase = rememberTextFieldState()
    var error by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val key = SshKeys.load(container.secrets.decryptText(identity.encryptedPrivateKey))
            val text = SshKeys.toOpenSshPrivateKey(key, identity.name, passphrase.text.toString().takeIf { it.isNotEmpty() }?.toCharArray())
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
