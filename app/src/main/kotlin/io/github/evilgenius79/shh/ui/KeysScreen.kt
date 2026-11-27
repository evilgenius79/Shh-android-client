@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.evilgenius79.shh.security.SecurityKeyClient
import io.github.evilgenius79.shh.security.SecurityKeyFinder
import io.github.evilgenius79.shh.security.SecurityKeyOutcome
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
fun KeysScreen(
    container: AppContainer,
    data: AppData,
    onBack: () -> Unit,
    /** How security keys are found. The default looks on NFC and USB; tests bring their own. */
    securityKeys: SecurityKeyFinder? = null,
) {
    var fabMenu by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var onSecurityKey by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Identity?>(null) }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.keys_title), onBack) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) { AppIcon(R.drawable.ic_add, stringResource(R.string.keys_add)) }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.keys_add_generate)) }, leadingIcon = { AppIcon(R.drawable.ic_key, null) }, onClick = {
                        fabMenu = false
                        generating = true
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.keys_add_import)) }, leadingIcon = { AppIcon(R.drawable.ic_upload, null) }, onClick = {
                        fabMenu = false
                        importing = true
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.keys_add_on_security_key)) }, leadingIcon = { AppIcon(R.drawable.ic_lock, null) }, onClick = {
                        fabMenu = false
                        onSecurityKey = true
                    })
                }
            }
        },
    ) { padding ->
        if (data.identities.isEmpty()) {
            EmptyState(
                R.drawable.ic_key,
                stringResource(R.string.keys_empty_title),
                stringResource(R.string.keys_empty_text),
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
                                Text(if (users > 0) pluralStringResource(R.plurals.keys_kind_used_by, users, identity.algorithm, users) else identity.algorithm)
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
    if (onSecurityKey) NewSecurityKeyDialog(container, securityKeys, onDone = { onSecurityKey = false })
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
        title = { Text(stringResource(R.string.keys_generate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                SelectField(stringResource(R.string.keys_type_label), algorithm, KeyAlgorithm.entries, { it.displayName }, { algorithm = it })
                Text(
                    stringResource(if (algorithm == KeyAlgorithm.ED25519) R.string.keys_generate_ed25519_hint else R.string.keys_generate_other_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.keys_generating))
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
            ) { Text(stringResource(R.string.keys_generate)) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Makes a new key on a security key (FIDO2): first what to call it and what kind it is to be,
 * then the security key itself. What is stored in the app is the public key and the handle the
 * hardware knows the key by.
 */
@Composable
private fun NewSecurityKeyDialog(container: AppContainer, finder: SecurityKeyFinder?, onDone: () -> Unit) {
    val nameState = rememberTextFieldState()
    var ed25519 by remember { mutableStateOf(true) }
    var verify by remember { mutableStateOf(false) }
    var decided by remember { mutableStateOf(false) }
    val name = nameState.text.toString().trim()

    if (!decided) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(stringResource(R.string.keys_security_key_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.keys_security_key_intro), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        state = nameState,
                        label = { Text(stringResource(R.string.label_name)) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = ed25519, onClick = { ed25519 = true }, label = { Text("Ed25519") })
                        FilterChip(selected = !ed25519, onClick = { ed25519 = false }, label = { Text("ECDSA P-256") })
                    }
                    Text(
                        stringResource(if (ed25519) R.string.keys_security_key_ed25519_hint else R.string.keys_security_key_ecdsa_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow(
                        stringResource(R.string.keys_security_key_pin_title),
                        stringResource(R.string.keys_security_key_pin_summary),
                        verify,
                        { verify = it },
                        Modifier.padding(horizontal = 0.dp),
                    )
                    Text(
                        stringResource(R.string.keys_security_key_server_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(enabled = name.isNotEmpty(), onClick = { decided = true }) { Text(stringResource(R.string.action_continue)) } },
            dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
        )
    } else {
        SecurityKeyDialog(
            key = Triple(name, ed25519, verify),
            task = remember(ed25519, verify) { SecurityKeyClient.creating(ed25519, verificationRequired = verify) },
            why = stringResource(R.string.keys_security_key_why, name),
            skipLabel = null,
            finder = finder,
            onDone = { outcome ->
                if (outcome is SecurityKeyOutcome.Done) container.store.saveIdentity(identityFrom(outcome.value, name, container.secrets))
                onDone()
            },
        )
    }
}

@Composable
private fun ImportKeyDialog(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
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
                        error = resources.getString(R.string.keys_import_too_large)
                    } else {
                        text = String(bytes, Charsets.UTF_8)
                        error = null
                    }
                }
            } catch (e: IOException) {
                error = resources.getString(R.string.keys_error_file_not_read, e.message)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.keys_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.label_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    text,
                    {
                        text = it
                        needsPassphrase = false
                        error = null
                    },
                    label = { Text(stringResource(R.string.keys_import_private_key_label)) },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { picker.launch("*/*") }) { Text(stringResource(R.string.keys_choose_file)) }
                if (needsPassphrase) SecretField(passphrase, stringResource(R.string.keys_import_passphrase_label), Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.keys_import_formats),
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
                        error = resources.getString(R.string.keys_import_needs_passphrase)
                    } catch (_: WrongPassphraseException) {
                        needsPassphrase = true
                        error = resources.getString(R.string.keys_import_wrong_passphrase)
                    } catch (e: IOException) {
                        error = e.message ?: resources.getString(R.string.keys_import_not_a_key)
                    }
                },
            ) { Text(stringResource(R.string.keys_import)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The certificate in [text], provided it is one for logging in with [identity]'s key.
 *
 * @throws IOException saying what is wrong with it otherwise, in a text from [resources] where the objection is the app's own.
 */
@Throws(IOException::class)
fun certificateFor(resources: Resources, identity: Identity, text: String): SshCertificate {
    val certificate = SshCertificate.parse(text)
    if (certificate.publicKey.toAuthorizedKey() != identity.publicKey) throw IOException(resources.getString(R.string.keys_certificate_other_key))
    if (certificate.isHostCertificate) throw IOException(resources.getString(R.string.keys_certificate_for_server))
    return certificate
}

/** One line about a certificate: what it is called, whom it is for and how long it lasts. */
fun describeCertificate(resources: Resources, certificate: SshCertificate, now: Date = Date()): String {
    // Certificates without an end carry the largest possible date.
    val endless = certificate.validBefore.time < 0 || certificate.validBefore.time > FAR_FUTURE_MS
    val until = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(certificate.validBefore)
    val name = certificate.keyId
    // Each combination is a text of its own rather than one put together from parts, so that a
    // translation can give every one of them the order of words its language wants.
    if (certificate.principals.isEmpty()) {
        return when {
            now.before(certificate.validAfter) -> resources.getString(R.string.keys_certificate_any_account_not_valid_yet, name)
            endless -> resources.getString(R.string.keys_certificate_any_account_endless, name)
            certificate.isValidAt(now) -> resources.getString(R.string.keys_certificate_any_account_valid_until, name, until)
            else -> resources.getString(R.string.keys_certificate_any_account_expired, name, until)
        }
    }
    val accounts = certificate.principals.joinToString(", ")
    return when {
        now.before(certificate.validAfter) -> resources.getString(R.string.keys_certificate_not_valid_yet, name, accounts)
        endless -> resources.getString(R.string.keys_certificate_endless, name, accounts)
        certificate.isValidAt(now) -> resources.getString(R.string.keys_certificate_valid_until, name, accounts, until)
        else -> resources.getString(R.string.keys_certificate_expired, name, accounts, until)
    }
}

private const val FAR_FUTURE_MS = 7_000_000_000_000L // the year 2191

@Composable
private fun KeyDetailsDialog(container: AppContainer, identity: Identity, usedBy: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
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
                if (identity.publicKey.startsWith("sk-")) {
                    Text(
                        stringResource(R.string.keys_details_on_security_key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.keys_public_key_heading), style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(publicLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.keys_public_key_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { copyToClipboard(context, resources.getString(R.string.keys_public_key_clip_label), publicLine) }) {
                        Text(stringResource(R.string.action_copy))
                    }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, publicLine)
                        context.startActivity(Intent.createChooser(send, resources.getString(R.string.keys_share_public_key)))
                    }) { Text(stringResource(R.string.action_share)) }
                    TextButton(onClick = { exporting = true }) { Text(stringResource(R.string.keys_export_private_key)) }
                }
                Text(stringResource(R.string.keys_certificate_heading), style = MaterialTheme.typography.labelLarge)
                if (certificate != null) {
                    Text(describeCertificate(resources, certificate), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { container.store.saveIdentity(identity.copy(certificate = null)) }) {
                        Text(stringResource(R.string.keys_certificate_remove))
                    }
                } else {
                    Text(
                        stringResource(R.string.keys_certificate_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { addingCertificate = true }) { Text(stringResource(R.string.keys_certificate_add)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        },
    )

    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.keys_delete_title, identity.name),
            text = if (usedBy > 0) pluralStringResource(R.plurals.keys_delete_text_in_use, usedBy, usedBy) else stringResource(R.string.keys_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
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
    val resources = LocalResources.current
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
            error = resources.getString(R.string.keys_error_file_not_read, e.message)
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.keys_certificate_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.keys_certificate_add_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    state = text,
                    label = { Text(stringResource(R.string.keys_certificate_label)) },
                    placeholder = { Text("ssh-ed25519-cert-v01@openssh.com AAAA…") },
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 6),
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { picker.launch("*/*") }) { Text(stringResource(R.string.keys_choose_file)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.text.isNotBlank(),
                onClick = {
                    try {
                        val certificate = certificateFor(resources, identity, text.text.toString())
                        container.store.saveIdentity(identity.copy(certificate = certificate.toLine()))
                        onDone()
                    } catch (e: IOException) {
                        error = e.message
                    }
                },
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ExportKeyDialog(container: AppContainer, identity: Identity, onDone: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val passphrase = rememberTextFieldState()
    var error by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val key = SshKeys.load(container.secrets.decryptText(identity.encryptedPrivateKey))
            val text = SshKeys.toOpenSshPrivateKey(key, identity.name, passphrase.text.toString().takeIf { it.isNotEmpty() }?.toCharArray())
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IOException(resources.getString(R.string.keys_reason_destination_not_opened))
            onDone()
        } catch (e: Exception) {
            error = resources.getString(R.string.keys_export_failed, e.message)
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.keys_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.keys_export_text))
                SecretField(
                    passphrase,
                    stringResource(R.string.keys_export_passphrase_label),
                    Modifier.fillMaxWidth(),
                    supportingText = stringResource(R.string.keys_export_passphrase_hint),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { saver.launch(identity.name.replace(Regex("[^A-Za-z0-9._-]"), "_")) }) {
                Text(stringResource(R.string.keys_export_choose_destination))
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_KEY_FILE_BYTES = 64 * 1024
