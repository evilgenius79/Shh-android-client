@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import android.app.KeyguardManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.evilgenius79.shh.AppContainer
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.Backup
import io.github.evilgenius79.shh.data.BackupException
import io.github.evilgenius79.shh.data.CursorStyle
import io.github.evilgenius79.shh.data.Settings
import io.github.evilgenius79.shh.data.ThemeMode
import io.github.evilgenius79.shh.terminal.ColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun SettingsScreen(container: AppContainer, data: AppData, onBack: () -> Unit, onEditKeys: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = data.settings
    val update: ((Settings) -> Settings) -> Unit = { transform -> container.store.updateSettings(transform) }
    var exporting by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                importText = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: IOException) {
                message = "The file could not be read: ${e.message}"
            }
        }
    }

    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            val field = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)

            SectionHeader("Appearance")
            SelectField(
                "Theme",
                settings.themeMode,
                ThemeMode.entries,
                {
                    when (it) {
                        ThemeMode.SYSTEM -> "Follow the system"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    }
                },
                { mode -> update { it.copy(themeMode = mode) } },
                field,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow("Use wallpaper colours", "Material You colours for the app's own screens", settings.dynamicColor, { on -> update { it.copy(dynamicColor = on) } })
            }

            SectionHeader("Terminal")
            SelectField(
                "Colours",
                ColorScheme.byId(settings.colorSchemeId),
                ColorScheme.ALL,
                { it.name },
                { scheme -> update { it.copy(colorSchemeId = scheme.id) } },
                field,
            )
            SelectField(
                "Text size",
                settings.fontSizeSp,
                (listOf(8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32) + settings.fontSizeSp).distinct().sorted(),
                { "$it sp" },
                { size -> update { it.copy(fontSizeSp = size) } },
                field,
            )
            Text(
                "You can also pinch the terminal to zoom.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SelectField(
                "Cursor",
                settings.cursorStyle,
                CursorStyle.entries,
                {
                    when (it) {
                        CursorStyle.BLOCK -> "Block"
                        CursorStyle.UNDERLINE -> "Underline"
                        CursorStyle.BAR -> "Bar"
                    }
                },
                { style -> update { it.copy(cursorStyle = style) } },
                field,
            )
            SwitchRow("Blinking cursor", null, settings.cursorBlink, { on -> update { it.copy(cursorBlink = on) } })
            SelectField(
                "Scrollback",
                settings.scrollbackLines,
                listOf(1000, 5000, 10000, 50000),
                { "$it lines" },
                { lines -> update { it.copy(scrollbackLines = lines) } },
                field,
            )
            Text(
                "Colours and scrollback apply to sessions opened from now on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SwitchRow("Extra keys", "Esc, Tab, Ctrl, Alt, arrows and more, above the keyboard", settings.showExtraKeys, { on -> update { it.copy(showExtraKeys = on) } })
            Entry(
                headline = "Keys of the bar",
                supporting = { Text(if (settings.keyBar.isEmpty()) "Choose which keys it shows, and where" else "Your own arrangement") },
                onClick = onEditKeys,
            )
            SwitchRow(
                "Hide the keys with a hardware keyboard",
                "While a keyboard is connected the bar makes room for the terminal",
                settings.hideKeysWithKeyboard,
                { on -> update { it.copy(hideKeysWithKeyboard = on) } },
            )
            SwitchRow(
                "Full keyboard features",
                "Voice typing, swipe typing and keyboards for other scripts. The keyboard may then correct what you type.",
                settings.fullKeyboard,
                { on -> update { it.copy(fullKeyboard = on) } },
            )
            SwitchRow("Vibrate on bell", null, settings.vibrateOnBell, { on -> update { it.copy(vibrateOnBell = on) } })
            SwitchRow(
                "Let servers set the clipboard",
                "Programs on the server may copy text to this device (OSC 52). They can never read it.",
                settings.allowRemoteClipboard,
                { on -> update { it.copy(allowRemoteClipboard = on) } },
            )

            SectionHeader("Sessions")
            SwitchRow(
                "Reconnect automatically",
                "When a connection drops, for example because the network changed",
                settings.autoReconnect,
                { on -> update { it.copy(autoReconnect = on) } },
            )
            SwitchRow(
                "Close a tab when its shell exits",
                "After exit or Ctrl+D. A session that ends on its own stays, so that you can read why.",
                settings.closeOnExit,
                { on -> update { it.copy(closeOnExit = on) } },
            )
            SwitchRow(
                "Notify on bell in the background",
                "When a session you are not looking at rings the bell, for example at the end of a long command",
                settings.notifyOnBell,
                { on -> update { it.copy(notifyOnBell = on) } },
            )
            SwitchRow("Keep the screen on", "While a terminal is showing", settings.keepScreenOn, { on -> update { it.copy(keepScreenOn = on) } })
            SwitchRow(
                "Stay awake in the background",
                "Keeps connections and port forwards alive when the screen is off. Uses more battery.",
                settings.keepAwake,
                { on -> update { it.copy(keepAwake = on) } },
            )

            SectionHeader("Security")
            val keyguard = ContextCompat.getSystemService(context, KeyguardManager::class.java)
            val secure = keyguard?.isDeviceSecure == true
            SwitchRow(
                "Lock the app",
                if (secure) "Ask for your fingerprint, face or screen lock when the app is opened"
                else "Set up a screen lock on this device to use this",
                settings.requireUnlock && secure,
                { on -> if (secure) update { it.copy(requireUnlock = on) } },
            )

            SectionHeader("Backup")
            Entry(
                headline = "Export backup",
                supporting = { Text("Hosts, keys, passwords, snippets and settings in one file, encrypted with a passphrase you choose") },
                leading = { AppIcon(R.drawable.ic_save, null) },
                onClick = { exporting = true },
            )
            Entry(
                headline = "Restore backup",
                supporting = { Text("Replaces everything in the app with the contents of a backup file") },
                leading = { AppIcon(R.drawable.ic_history, null) },
                onClick = { importPicker.launch(arrayOf("*/*")) },
            )
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }

    if (exporting) {
        ExportBackupDialog(container, data, onDone = { result ->
            exporting = false
            if (result != null) message = result
        })
    }
    importText?.let { text ->
        RestoreBackupDialog(container, text, onDone = { result ->
            importText = null
            if (result != null) message = result
        })
    }
}

@Composable
private fun ExportBackupDialog(container: AppContainer, data: AppData, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val passphraseState = rememberTextFieldState()
    val repeatState = rememberTextFieldState()
    val passphrase = passphraseState.text.toString()
    val repeat = repeatState.text.toString()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val text = Backup.export(data, container.secrets, passphrase.toCharArray())
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("The destination could not be opened")
                }
                "Backup saved."
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            }
            onDone(result)
        }
    }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Export backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The backup contains your private keys and saved passwords. It is encrypted with this passphrase; without it the backup cannot be restored.")
                SecretField(passphraseState, "Passphrase", Modifier.fillMaxWidth())
                SecretField(repeatState, "Repeat passphrase", Modifier.fillMaxWidth())
                if (repeat.isNotEmpty() && repeat != passphrase) {
                    Text("The passphrases do not match.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = passphrase.length >= 8 && passphrase == repeat, onClick = { saver.launch("shh-backup.shhbackup") }) {
                Text("Choose where to save")
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun RestoreBackupDialog(container: AppContainer, text: String, onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val passphraseState = rememberTextFieldState()
    val passphrase = passphraseState.text.toString()
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onDone(null) },
        title = { Text("Restore backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Everything currently in the app (hosts, keys, known hosts, snippets, settings) is replaced by the backup.")
                SecretField(passphraseState, "Passphrase of the backup", Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = passphrase.isNotEmpty() && !working,
                onClick = {
                    working = true
                    scope.launch {
                        try {
                            val restored = withContext(Dispatchers.Default) { Backup.import(text, container.secrets, passphrase.toCharArray()) }
                            container.store.replaceAll(restored)
                            onDone("Backup restored: ${restored.hosts.size} hosts, ${restored.identities.size} keys.")
                        } catch (e: BackupException) {
                            error = e.message
                            working = false
                        }
                    }
                },
            ) { Text("Restore") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = { onDone(null) }) { Text("Cancel") } },
    )
}
