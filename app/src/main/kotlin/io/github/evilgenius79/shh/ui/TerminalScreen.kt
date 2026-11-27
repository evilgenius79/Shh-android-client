@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.CursorStyle
import io.github.evilgenius79.shh.data.Identity
import io.github.evilgenius79.shh.data.Settings
import io.github.evilgenius79.shh.data.Snippet
import io.github.evilgenius79.shh.session.SessionPrompt
import io.github.evilgenius79.shh.session.SessionState
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.HostKeyDecision
import io.github.evilgenius79.shh.ssh.HostKeyStatus
import io.github.evilgenius79.shh.ssh.SshFailure
import io.github.evilgenius79.shh.terminal.ColorScheme
import io.github.evilgenius79.shh.terminal.CursorShape
import io.github.evilgenius79.shh.terminal.TerminalKey
import io.github.evilgenius79.shh.terminal.TerminalView

@Composable
fun TerminalScreen(
    sessions: List<TerminalSession>,
    active: TerminalSession,
    settings: Settings,
    snippets: List<Snippet>,
    identities: List<Identity>,
    onSelect: (TerminalSession) -> Unit,
    onClose: (TerminalSession) -> Unit,
    onNewSession: () -> Unit,
    onOpenFiles: (TerminalSession) -> Unit,
    onFontSize: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val state by active.state.collectAsStateCompat()
    val title by active.title.collectAsStateCompat()
    val prompt by active.prompt.collectAsStateCompat()
    var menuOpen by remember { mutableStateOf(false) }
    var showSnippets by remember { mutableStateOf(false) }
    var showForwards by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var showInstallKey by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    // Bumped when the colour scheme changes so the view redraws.
    var colorVersion by remember { mutableIntStateOf(0) }

    if (settings.keepScreenOn) {
        val window = LocalActivity.current?.window
        DisposableEffect(window) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (val s = state) {
                                    is SessionState.Connecting -> "Connecting…"
                                    SessionState.Connected -> active.host.address
                                    is SessionState.Disconnected -> s.message
                                },
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state is SessionState.Disconnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, "Hosts") } },
                    actions = {
                        IconButton(onClick = { terminalView?.pasteFromClipboard() }) { AppIcon(R.drawable.ic_content_paste, "Paste") }
                        IconButton(onClick = { menuOpen = true }) { AppIcon(R.drawable.ic_more_vert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            @Composable
                            fun entry(label: String, icon: Int, enabled: Boolean = true, action: () -> Unit) {
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = { AppIcon(icon, null) },
                                    enabled = enabled,
                                    onClick = {
                                        menuOpen = false
                                        action()
                                    },
                                )
                            }
                            entry("New session", R.drawable.ic_add) { onNewSession() }
                            entry("Snippets", R.drawable.ic_code, enabled = active.isConnected) { showSnippets = true }
                            entry("Files", R.drawable.ic_folder, enabled = active.isConnected) { onOpenFiles(active) }
                            entry("Port forwards", R.drawable.ic_swap_horiz, enabled = active.isConnected) { showForwards = true }
                            entry("Install a public key", R.drawable.ic_key, enabled = active.isConnected && identities.isNotEmpty()) { showInstallKey = true }
                            HorizontalDivider()
                            entry("Colours", R.drawable.ic_palette) { showColors = true }
                            entry("Larger text", R.drawable.ic_text_fields) { onFontSize((settings.fontSizeSp + 1).coerceAtMost(TerminalView.MAX_FONT_SP.toInt())) }
                            entry("Smaller text", R.drawable.ic_text_fields) { onFontSize((settings.fontSizeSp - 1).coerceAtLeast(TerminalView.MIN_FONT_SP.toInt())) }
                            entry("Reset terminal", R.drawable.ic_restart_alt) {
                                synchronized(active.emulator) { active.emulator.reset() }
                                terminalView?.invalidate()
                            }
                            HorizontalDivider()
                            if (state is SessionState.Disconnected) entry("Reconnect", R.drawable.ic_refresh) { active.reconnect() }
                            entry("Close session", R.drawable.ic_power_settings_new) { onClose(active) }
                        }
                    },
                )
                if (sessions.size > 1) {
                    val index = sessions.indexOf(active).coerceAtLeast(0)
                    PrimaryScrollableTabRow(selectedTabIndex = index, edgePadding = 8.dp) {
                        for (session in sessions) {
                            val tabTitle by session.title.collectAsStateCompat()
                            Tab(
                                selected = session === active,
                                onClick = { onSelect(session) },
                                text = { Text(tabTitle, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        val scheme = remember(active, colorVersion) { active.emulator.colors.scheme }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .background(Color(0xFF000000 or scheme.background.toLong())),
        ) {
            Box(Modifier.weight(1f)) {
                AndroidView(
                    factory = { context ->
                        TerminalView(context).also { view ->
                            terminalView = view
                            view.onModifiersConsumed = {
                                ctrl = false
                                alt = false
                            }
                        }
                    },
                    update = { view ->
                        view.attach(active)
                        view.fontSizeSp = settings.fontSizeSp.toFloat()
                        view.cursorBlink = settings.cursorBlink
                        view.cursorShape = when (settings.cursorStyle) {
                            CursorStyle.BLOCK -> CursorShape.BLOCK
                            CursorStyle.UNDERLINE -> CursorShape.UNDERLINE
                            CursorStyle.BAR -> CursorShape.BAR
                        }
                        view.ctrlLatched = ctrl
                        view.altLatched = alt
                        view.onFontSizeChanged = { onFontSize(it.toInt()) }
                        // Read so that a colour change triggers this block and repaints the view.
                        if (colorVersion >= 0) view.invalidate()
                    },
                    onRelease = { view -> view.attach(null) },
                    modifier = Modifier.fillMaxSize(),
                )
                (state as? SessionState.Connecting)?.let { ConnectingOverlay(it, onCancel = { onClose(active) }) }
            }
            // Below the terminal rather than on top of it, so the last lines of output and the
            // reason for the disconnect stay readable.
            (state as? SessionState.Disconnected)?.let {
                DisconnectedBar(it, onReconnect = { active.reconnect() }, onClose = { onClose(active) })
            }
            if (settings.showExtraKeys) {
                ExtraKeys(
                    ctrl = ctrl,
                    alt = alt,
                    onCtrl = { ctrl = !ctrl },
                    onAlt = { alt = !alt },
                    onKey = { key -> terminalView?.sendKey(key, 0) },
                    onText = { text -> terminalView?.typed(text) },
                    onToggleKeyboard = { terminalView?.showKeyboard() },
                )
            }
        }
    }

    when (val p = prompt) {
        is SessionPrompt.HostKey -> HostKeyDialog(p)
        is SessionPrompt.Password -> PasswordDialog(p)
        is SessionPrompt.Challenge -> ChallengeDialog(p)
        null -> Unit
    }

    if (showSnippets) {
        AlertDialog(
            onDismissRequest = { showSnippets = false },
            title = { Text("Snippets") },
            text = {
                if (snippets.isEmpty()) {
                    Text("No snippets yet. Add some from the host list menu.")
                } else {
                    Column {
                        for (snippet in snippets) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSnippets = false
                                        active.paste(snippet.command + if (snippet.runImmediately) "\n" else "")
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(snippet.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    snippet.command,
                                    maxLines = 2,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSnippets = false }) { Text("Close") } },
        )
    }
    if (showForwards) SessionForwardsDialog(active, onDismiss = { showForwards = false })
    if (showInstallKey) InstallKeyDialog(active, identities, onDismiss = { showInstallKey = false })
    if (showColors) {
        AlertDialog(
            onDismissRequest = { showColors = false },
            title = { Text("Colours for this session") },
            text = {
                Column {
                    for (option in ColorScheme.ALL) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    synchronized(active.emulator) { active.emulator.colors.applyScheme(option) }
                                    colorVersion++
                                    showColors = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SchemeSwatch(option)
                            Text(option.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showColors = false }) { Text("Close") } },
        )
    }
}

/** A small preview of a colour scheme: its background with a few of its colours on top. */
@Composable
fun SchemeSwatch(scheme: ColorScheme) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF000000 or scheme.background.toLong()))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text("ab", color = Color(0xFF000000 or scheme.foreground.toLong()), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        for (index in intArrayOf(1, 2, 3, 4, 5, 6)) {
            Text("█", color = Color(0xFF000000 or scheme.ansi(index).toLong()), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ConnectingOverlay(state: SessionState.Connecting, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                for (message in state.messages.takeLast(3)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun DisconnectedBar(state: SessionState.Disconnected, onReconnect: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            hintFor(state.failure)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Close") }
                Button(onClick = onReconnect) { Text("Reconnect") }
            }
        }
    }
}

/** A suggestion for what the user could try, for the failures where there is an obvious one. */
private fun hintFor(failure: SshFailure?): String? = when (failure) {
    SshFailure.TIMEOUT, SshFailure.UNREACHABLE ->
        "Check the address and your network. For servers on your Wi-Fi, Android 17 and later also need the \"Nearby devices\" permission for this app."
    SshFailure.REFUSED -> "The host is reachable but nothing is listening on that port."
    SshFailure.UNKNOWN_HOST -> "The name could not be resolved. Check the spelling and your connection."
    SshFailure.PROTOCOL -> "If this is an old device, try \"Allow legacy algorithms\" in the host settings."
    SshFailure.AUTH_FAILED -> "Check the user name, password or key for this host."
    else -> null
}

/** The row of keys a phone keyboard lacks. Ctrl and Alt latch and apply to the next key. */
@Composable
private fun ExtraKeys(
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (TerminalKey) -> Unit,
    onText: (String) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyCap("ESC") { onKey(TerminalKey.ESCAPE) }
            KeyCap("TAB") { onKey(TerminalKey.TAB) }
            KeyCap("CTRL", active = ctrl, onClick = onCtrl)
            KeyCap("ALT", active = alt, onClick = onAlt)
            KeyCap("←", description = "Left") { onKey(TerminalKey.LEFT) }
            KeyCap("↓", description = "Down") { onKey(TerminalKey.DOWN) }
            KeyCap("↑", description = "Up") { onKey(TerminalKey.UP) }
            KeyCap("→", description = "Right") { onKey(TerminalKey.RIGHT) }
            for (symbol in listOf("/", "|", "-", "~", "_", ":", "*")) {
                KeyCap(symbol) { onText(symbol) }
            }
            KeyCap("HOME") { onKey(TerminalKey.HOME) }
            KeyCap("END") { onKey(TerminalKey.END) }
            KeyCap("PGUP") { onKey(TerminalKey.PAGE_UP) }
            KeyCap("PGDN") { onKey(TerminalKey.PAGE_DOWN) }
            KeyCap("DEL") { onKey(TerminalKey.DELETE) }
            for ((label, key) in listOf(
                "F1" to TerminalKey.F1, "F2" to TerminalKey.F2, "F3" to TerminalKey.F3, "F4" to TerminalKey.F4,
                "F5" to TerminalKey.F5, "F6" to TerminalKey.F6, "F7" to TerminalKey.F7, "F8" to TerminalKey.F8,
                "F9" to TerminalKey.F9, "F10" to TerminalKey.F10, "F11" to TerminalKey.F11, "F12" to TerminalKey.F12,
            )) {
                KeyCap(label) { onKey(key) }
            }
            IconButton(onClick = onToggleKeyboard) { AppIcon(R.drawable.ic_keyboard, "Show keyboard") }
        }
    }
}

@Composable
private fun KeyCap(label: String, active: Boolean = false, description: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .defaultMinSize(minWidth = 44.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.primary else colors.surfaceVariant)
            .clickable(role = Role.Button, onClickLabel = description ?: label, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) colors.onPrimary else colors.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun HostKeyDialog(prompt: SessionPrompt.HostKey) {
    val request = prompt.request
    val changed = request.status == HostKeyStatus.CHANGED
    AlertDialog(
        onDismissRequest = { prompt.respond(HostKeyDecision.REJECT) },
        icon = {
            AppIcon(
                if (changed) R.drawable.ic_warning else R.drawable.ic_fingerprint,
                null,
                tint = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                when (request.status) {
                    HostKeyStatus.CHANGED -> "Host key has changed"
                    HostKeyStatus.NEW_KEY_TYPE -> "New key for a known host"
                    else -> "Unknown host"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (request.status) {
                        HostKeyStatus.CHANGED ->
                            "${request.host} presented a different key than the one you trusted before. " +
                                "Unless you know the server was reinstalled, someone may be intercepting the connection."
                        HostKeyStatus.NEW_KEY_TYPE ->
                            "${request.host} is known, but presented a key of a type you have not seen from it."
                        else -> "You have not connected to ${request.host}:${request.port} before. Check that the fingerprint matches the server's."
                    },
                )
                Text(request.key.type, style = MaterialTheme.typography.labelLarge)
                Text(request.key.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (changed) {
                    for (old in request.knownKeys.filter { it.type == request.key.type }) {
                        Text("Previously trusted:", style = MaterialTheme.typography.labelLarge)
                        Text(old.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { prompt.respond(HostKeyDecision.ACCEPT_AND_REMEMBER) }) {
                Text(if (changed) "Replace and connect" else "Trust and connect")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { prompt.respond(HostKeyDecision.REJECT) }) { Text("Cancel") }
                if (!changed) TextButton(onClick = { prompt.respond(HostKeyDecision.ACCEPT_ONCE) }) { Text("Just once") }
            }
        },
    )
}

@Composable
private fun PasswordDialog(prompt: SessionPrompt.Password) {
    val password = remember(prompt) { TextFieldState() }
    AlertDialog(
        onDismissRequest = { prompt.respond(null) },
        title = { Text("Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (prompt.attempt > 1) "That password was not accepted. Try again for ${prompt.target.username}@${prompt.target.host}."
                    else "Password for ${prompt.target.username}@${prompt.target.host}",
                )
                SecretField(password, "Password", Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { prompt.respond(password.text.toString().toCharArray()) }) { Text("Log in") } },
        dismissButton = { TextButton(onClick = { prompt.respond(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun ChallengeDialog(prompt: SessionPrompt.Challenge) {
    val challenge = prompt.challenge
    val question = challenge.prompts.first()
    val answer = remember(prompt) { TextFieldState() }
    AlertDialog(
        onDismissRequest = { prompt.respond(null) },
        title = { Text(challenge.name.ifBlank { "Verification" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (challenge.instruction.isNotBlank()) Text(challenge.instruction)
                if (question.echo) {
                    OutlinedTextField(
                        state = answer,
                        label = { Text(question.text.trim()) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    SecretField(answer, question.text.trim(), Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = { prompt.respond(listOf(answer.text.toString())) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { prompt.respond(null) }) { Text("Cancel") } },
    )
}
