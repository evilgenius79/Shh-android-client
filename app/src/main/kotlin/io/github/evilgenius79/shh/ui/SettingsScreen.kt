@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
import io.github.evilgenius79.shh.terminal.FontException
import io.github.evilgenius79.shh.terminal.TerminalFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun SettingsScreen(container: AppContainer, data: AppData, onBack: () -> Unit, onEditKeys: () -> Unit = {}) {
    val context = LocalContext.current
    // For the texts that are made outside composition: by the file pickers and for the choices of a field.
    val resources = LocalResources.current
    val settings = data.settings
    val update: ((Settings) -> Settings) -> Unit = { transform -> container.store.updateSettings(transform) }
    var exporting by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    var fontProblem by remember { mutableStateOf<String?>(null) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val stored = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IOException(resources.getString(R.string.settings_font_not_opened))
                        input.use { TerminalFonts.install(context, it) }
                    }
                    val name = fontNameOf(resources, displayName(context, uri))
                    update { it.copy(terminalFont = stored, terminalFontName = name) }
                } catch (e: FontException) {
                    fontProblem = e.message
                } catch (e: IOException) {
                    fontProblem = resources.getString(R.string.common_file_not_read, e.message)
                } catch (e: SecurityException) {
                    fontProblem = resources.getString(R.string.common_file_not_read, e.message)
                }
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                importText = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: IOException) {
                message = resources.getString(R.string.common_file_not_read, e.message)
            }
        }
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            val field = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)

            SectionHeader(stringResource(R.string.settings_section_appearance))
            SelectField(
                stringResource(R.string.settings_theme),
                settings.themeMode,
                ThemeMode.entries,
                {
                    resources.getString(
                        when (it) {
                            ThemeMode.SYSTEM -> R.string.settings_theme_system
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                        },
                    )
                },
                { mode -> update { it.copy(themeMode = mode) } },
                field,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(
                    stringResource(R.string.settings_dynamic_color),
                    stringResource(R.string.settings_dynamic_color_summary),
                    settings.dynamicColor,
                    { on -> update { it.copy(dynamicColor = on) } },
                )
            }

            SectionHeader(stringResource(R.string.settings_section_terminal))
            SelectField(
                stringResource(R.string.settings_colors),
                ColorScheme.byId(settings.colorSchemeId),
                ColorScheme.ALL,
                { it.name },
                { scheme -> update { it.copy(colorSchemeId = scheme.id) } },
                field,
            )
            SelectField(
                stringResource(R.string.settings_text_size),
                settings.fontSizeSp,
                (listOf(8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32) + settings.fontSizeSp).distinct().sorted(),
                { resources.getString(R.string.settings_text_size_value, it) },
                { size -> update { it.copy(fontSizeSp = size) } },
                field,
            )
            // A backup restored on another device names a font whose file is not here.
            val ownFont = settings.terminalFont?.takeIf { TerminalFonts.file(context, it).isFile }
            Entry(
                headline = stringResource(R.string.settings_font),
                supporting = {
                    Text(
                        if (ownFont != null) {
                            stringResource(R.string.settings_font_own, settings.terminalFontName ?: stringResource(R.string.settings_font_unnamed))
                        } else {
                            stringResource(R.string.settings_font_builtin)
                        },
                    )
                },
                onClick = { fontPicker.launch(arrayOf("*/*")) },
            )
            if (ownFont != null) {
                Entry(
                    headline = stringResource(R.string.settings_font_use_builtin),
                    onClick = {
                        update { it.copy(terminalFont = null, terminalFontName = null) }
                        TerminalFonts.remove(context)
                    },
                )
            }
            Text(
                stringResource(R.string.settings_pinch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SelectField(
                stringResource(R.string.settings_cursor),
                settings.cursorStyle,
                CursorStyle.entries,
                {
                    resources.getString(
                        when (it) {
                            CursorStyle.BLOCK -> R.string.settings_cursor_block
                            CursorStyle.UNDERLINE -> R.string.settings_cursor_underline
                            CursorStyle.BAR -> R.string.settings_cursor_bar
                        },
                    )
                },
                { style -> update { it.copy(cursorStyle = style) } },
                field,
            )
            SwitchRow(stringResource(R.string.settings_cursor_blink), null, settings.cursorBlink, { on -> update { it.copy(cursorBlink = on) } })
            SelectField(
                stringResource(R.string.settings_scrollback),
                settings.scrollbackLines,
                listOf(1000, 5000, 10000, 50000),
                { resources.getQuantityString(R.plurals.settings_scrollback_lines, it, it) },
                { lines -> update { it.copy(scrollbackLines = lines) } },
                field,
            )
            Text(
                stringResource(R.string.settings_new_sessions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SwitchRow(
                stringResource(R.string.settings_extra_keys),
                stringResource(R.string.settings_extra_keys_summary),
                settings.showExtraKeys,
                { on -> update { it.copy(showExtraKeys = on) } },
            )
            Entry(
                headline = stringResource(R.string.settings_key_bar),
                supporting = {
                    Text(stringResource(if (settings.keyBar.isEmpty()) R.string.settings_key_bar_standard else R.string.settings_key_bar_own))
                },
                onClick = onEditKeys,
            )
            SwitchRow(
                stringResource(R.string.settings_hide_keys_with_keyboard),
                stringResource(R.string.settings_hide_keys_with_keyboard_summary),
                settings.hideKeysWithKeyboard,
                { on -> update { it.copy(hideKeysWithKeyboard = on) } },
            )
            SwitchRow(
                stringResource(R.string.settings_full_keyboard),
                stringResource(R.string.settings_full_keyboard_summary),
                settings.fullKeyboard,
                { on -> update { it.copy(fullKeyboard = on) } },
            )
            SwitchRow(stringResource(R.string.settings_vibrate_on_bell), null, settings.vibrateOnBell, { on -> update { it.copy(vibrateOnBell = on) } })
            SwitchRow(
                stringResource(R.string.settings_remote_clipboard),
                stringResource(R.string.settings_remote_clipboard_summary),
                settings.allowRemoteClipboard,
                { on -> update { it.copy(allowRemoteClipboard = on) } },
            )

            SectionHeader(stringResource(R.string.settings_section_sessions))
            SwitchRow(
                stringResource(R.string.settings_auto_reconnect),
                stringResource(R.string.settings_auto_reconnect_summary),
                settings.autoReconnect,
                { on -> update { it.copy(autoReconnect = on) } },
            )
            SwitchRow(
                stringResource(R.string.settings_close_on_exit),
                stringResource(R.string.settings_close_on_exit_summary),
                settings.closeOnExit,
                { on -> update { it.copy(closeOnExit = on) } },
            )
            SwitchRow(
                stringResource(R.string.settings_notify_on_bell),
                stringResource(R.string.settings_notify_on_bell_summary),
                settings.notifyOnBell,
                { on -> update { it.copy(notifyOnBell = on) } },
            )
            SwitchRow(
                stringResource(R.string.settings_keep_screen_on),
                stringResource(R.string.settings_keep_screen_on_summary),
                settings.keepScreenOn,
                { on -> update { it.copy(keepScreenOn = on) } },
            )
            SwitchRow(
                stringResource(R.string.settings_keep_awake),
                stringResource(R.string.settings_keep_awake_summary),
                settings.keepAwake,
                { on -> update { it.copy(keepAwake = on) } },
            )

            SectionHeader(stringResource(R.string.settings_section_security))
            val keyguard = ContextCompat.getSystemService(context, KeyguardManager::class.java)
            val secure = keyguard?.isDeviceSecure == true
            SwitchRow(
                stringResource(R.string.settings_lock_app),
                stringResource(if (secure) R.string.settings_lock_app_summary else R.string.settings_lock_app_unavailable),
                settings.requireUnlock && secure,
                { on -> if (secure) update { it.copy(requireUnlock = on) } },
            )

            SectionHeader(stringResource(R.string.settings_section_backup))
            Entry(
                headline = stringResource(R.string.backup_export_title),
                supporting = { Text(stringResource(R.string.backup_export_summary)) },
                leading = { AppIcon(R.drawable.ic_save, null) },
                onClick = { exporting = true },
            )
            Entry(
                headline = stringResource(R.string.backup_restore_title),
                supporting = { Text(stringResource(R.string.backup_restore_summary)) },
                leading = { AppIcon(R.drawable.ic_history, null) },
                onClick = { importPicker.launch(arrayOf("*/*")) },
            )
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }

    fontProblem?.let {
        AlertDialog(
            onDismissRequest = { fontProblem = null },
            title = { Text(stringResource(R.string.settings_font_problem_title)) },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { fontProblem = null }) { Text(stringResource(R.string.action_ok)) } },
        )
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

/** What the document behind [uri] is called, as far as its provider says. */
private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (e: RuntimeException) {
    // Providers are other apps' code. The name is only for show, so none will do.
    null
}

/** A font's name for the settings, from the name of its file: "FiraCode-Regular.ttf" is "FiraCode-Regular". */
internal fun fontNameOf(resources: Resources, fileName: String?): String {
    val name = fileName.orEmpty().substringAfterLast('/').trim()
    val bare = if (name.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "ttc", "otc")) name.substringBeforeLast('.') else name
    return bare.take(60).ifBlank { resources.getString(R.string.settings_font_unnamed) }
}

@Composable
private fun ExportBackupDialog(container: AppContainer, data: AppData, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
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
                        ?: throw IOException(resources.getString(R.string.backup_destination_not_opened))
                }
                resources.getString(R.string.backup_saved)
            } catch (e: Exception) {
                resources.getString(R.string.backup_failed, e.message)
            }
            onDone(result)
        }
    }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.backup_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_export_explanation))
                SecretField(passphraseState, stringResource(R.string.backup_passphrase), Modifier.fillMaxWidth())
                SecretField(repeatState, stringResource(R.string.backup_passphrase_repeat), Modifier.fillMaxWidth())
                if (repeat.isNotEmpty() && repeat != passphrase) {
                    Text(stringResource(R.string.backup_passphrases_differ), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = passphrase.length >= 8 && passphrase == repeat, onClick = { saver.launch("shh-backup.shhbackup") }) {
                Text(stringResource(R.string.backup_choose_destination))
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RestoreBackupDialog(container: AppContainer, text: String, onDone: (String?) -> Unit) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val passphraseState = rememberTextFieldState()
    val passphrase = passphraseState.text.toString()
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onDone(null) },
        title = { Text(stringResource(R.string.backup_restore_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_restore_explanation))
                SecretField(passphraseState, stringResource(R.string.backup_restore_passphrase), Modifier.fillMaxWidth())
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
                            val hosts = restored.hosts.size
                            val keys = restored.identities.size
                            onDone(
                                resources.getString(
                                    R.string.backup_restored,
                                    resources.getQuantityString(R.plurals.backup_restored_hosts, hosts, hosts),
                                    resources.getQuantityString(R.plurals.backup_restored_keys, keys, keys),
                                ),
                            )
                        } catch (e: BackupException) {
                            error = e.message
                            working = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.backup_restore_action)) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = { onDone(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
