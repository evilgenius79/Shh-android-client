@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.CursorStyle
import io.github.evilgenius79.shh.data.Identity
import io.github.evilgenius79.shh.data.Settings
import io.github.evilgenius79.shh.data.Snippet
import io.github.evilgenius79.shh.session.AgentAnswer
import io.github.evilgenius79.shh.session.SessionManager
import io.github.evilgenius79.shh.session.SessionPrompt
import io.github.evilgenius79.shh.session.SessionState
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.HostKeyDecision
import io.github.evilgenius79.shh.ssh.HostKeyStatus
import io.github.evilgenius79.shh.ssh.SshFailure
import io.github.evilgenius79.shh.terminal.ColorScheme
import io.github.evilgenius79.shh.terminal.CursorShape
import io.github.evilgenius79.shh.terminal.ModifierState
import io.github.evilgenius79.shh.terminal.TerminalFonts
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
    onHelp: () -> Unit,
    onBack: () -> Unit,
) {
    val state by active.state.collectAsStateCompat()
    val title by active.title.collectAsStateCompat()
    val prompt by active.prompt.collectAsStateCompat()
    // Over Mosh there is no SSH connection, and so nothing of what only SSH can do.
    val overMosh by active.isMosh.collectAsStateCompat()
    val silentForMs by active.silentForMs.collectAsStateCompat()
    var menuOpen by remember { mutableStateOf(false) }
    var showSnippets by remember { mutableStateOf(false) }
    var showForwards by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var showInstallKey by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(ModifierState.OFF) }
    var alt by remember { mutableStateOf(ModifierState.OFF) }
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
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (val s = state) {
                                    is SessionState.Connecting -> stringResource(R.string.terminal_connecting)
                                    SessionState.Connected -> active.host.address
                                    is SessionState.Reconnecting -> stringResource(R.string.terminal_reconnecting)
                                    is SessionState.Disconnected -> s.message
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state is SessionState.Disconnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.terminal_back_to_hosts)) } },
                    actions = {
                        IconButton(onClick = { terminalView?.pasteFromClipboard() }) {
                            AppIcon(R.drawable.ic_content_paste, stringResource(R.string.terminal_paste))
                        }
                        IconButton(onClick = { menuOpen = true }) { AppIcon(R.drawable.ic_more_vert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            @Composable
                            fun entry(@StringRes label: Int, icon: Int, enabled: Boolean = true, action: () -> Unit) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    leadingIcon = { AppIcon(icon, null) },
                                    enabled = enabled,
                                    onClick = {
                                        menuOpen = false
                                        action()
                                    },
                                )
                            }
                            entry(R.string.terminal_menu_new_session, R.drawable.ic_add) { onNewSession() }
                            entry(R.string.terminal_menu_snippets, R.drawable.ic_code, enabled = active.isConnected) { showSnippets = true }
                            entry(R.string.terminal_menu_files, R.drawable.ic_folder, enabled = active.isConnected && !overMosh) { onOpenFiles(active) }
                            entry(R.string.terminal_menu_port_forwards, R.drawable.ic_swap_horiz, enabled = active.isConnected && !overMosh) { showForwards = true }
                            entry(R.string.terminal_menu_install_key, R.drawable.ic_key, enabled = active.isConnected && !overMosh && identities.isNotEmpty()) {
                                showInstallKey = true
                            }
                            HorizontalDivider()
                            entry(R.string.terminal_menu_colours, R.drawable.ic_palette) { showColors = true }
                            entry(R.string.terminal_menu_larger_text, R.drawable.ic_text_fields) {
                                onFontSize((settings.fontSizeSp + 1).coerceAtMost(TerminalView.MAX_FONT_SP.toInt()))
                            }
                            entry(R.string.terminal_menu_smaller_text, R.drawable.ic_text_fields) {
                                onFontSize((settings.fontSizeSp - 1).coerceAtLeast(TerminalView.MIN_FONT_SP.toInt()))
                            }
                            entry(R.string.terminal_menu_show_keyboard, R.drawable.ic_keyboard) { terminalView?.showKeyboard() }
                            entry(R.string.terminal_menu_how_to_use, R.drawable.ic_help) { onHelp() }
                            entry(R.string.terminal_menu_reset, R.drawable.ic_restart_alt) {
                                active.resetTerminal()
                                terminalView?.invalidate()
                            }
                            HorizontalDivider()
                            if (state is SessionState.Disconnected) entry(R.string.terminal_reconnect, R.drawable.ic_refresh) { active.reconnect() }
                            entry(R.string.terminal_menu_close_session, R.drawable.ic_power_settings_new) { onClose(active) }
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
                                text = { Text(tabTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                            // A one-shot Ctrl or Alt was used up by a key: show it as off again.
                            view.onModifiersConsumed = {
                                ctrl = view.ctrl
                                alt = view.alt
                            }
                        }
                    },
                    update = { view ->
                        view.attach(active)
                        view.fontSizeSp = settings.fontSizeSp.toFloat()
                        view.fontFile = settings.terminalFont?.let { TerminalFonts.file(view.context, it) }
                        view.cursorBlink = settings.cursorBlink
                        view.cursorShape = when (settings.cursorStyle) {
                            CursorStyle.BLOCK -> CursorShape.BLOCK
                            CursorStyle.UNDERLINE -> CursorShape.UNDERLINE
                            CursorStyle.BAR -> CursorShape.BAR
                        }
                        view.ctrl = ctrl
                        view.alt = alt
                        view.fullKeyboard = settings.fullKeyboard
                        view.onFontSizeChanged = { onFontSize(it.toInt()) }
                        // Read so that a colour change triggers this block and repaints the view.
                        if (colorVersion >= 0) view.invalidate()
                    },
                    onRelease = { view -> view.attach(null) },
                    modifier = Modifier.fillMaxSize(),
                )
                // Keys belong to the terminal from the start. Without this a hardware keyboard
                // would steer the buttons of the top bar until the terminal was touched.
                LaunchedEffect(terminalView, active) { terminalView?.requestFocus() }
                (state as? SessionState.Connecting)?.let { ConnectingOverlay(it, onCancel = { onClose(active) }) }
            }
            // Below the terminal rather than on top of it, so the last lines of output and the
            // reason for the disconnect stay readable.
            (state as? SessionState.Disconnected)?.let {
                DisconnectedBar(it, onReconnect = { active.reconnect() }, onClose = { onClose(active) })
            }
            (state as? SessionState.Reconnecting)?.let {
                ReconnectingBar(it, onTryNow = { active.retryNow() }, onStop = { active.stopReconnecting() })
            }
            if (overMosh && silentForMs > 0) MoshSilentBar(silentForMs)
            if (keyBarShown(settings)) {
                ExtraKeys(
                    ctrl = ctrl,
                    alt = alt,
                    onCtrl = { ctrl = it },
                    onAlt = { alt = it },
                    onKey = { key -> terminalView?.sendKey(key, 0) },
                    onText = { text -> terminalView?.typed(text) },
                    layout = settings.keyBar,
                )
            }
        }
    }

    when (val p = prompt) {
        is SessionPrompt.HostKey -> HostKeyDialog(p)
        is SessionPrompt.Password -> PasswordDialog(p)
        is SessionPrompt.Challenge -> ChallengeDialog(p)
        is SessionPrompt.AgentUse -> AgentUseDialog(p)
        is SessionPrompt.SecurityKey -> SecurityKeyDialog(p)
        null -> Unit
    }

    if (showSnippets) {
        AlertDialog(
            onDismissRequest = { showSnippets = false },
            title = { Text(stringResource(R.string.terminal_snippets_title)) },
            text = {
                if (snippets.isEmpty()) {
                    Text(stringResource(R.string.terminal_snippets_empty))
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
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSnippets = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    if (showForwards) SessionForwardsDialog(active, onDismiss = { showForwards = false })
    if (showInstallKey) InstallKeyDialog(active, identities, onDismiss = { showInstallKey = false })
    if (showColors) {
        AlertDialog(
            onDismissRequest = { showColors = false },
            title = { Text(stringResource(R.string.terminal_colours_title)) },
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
            confirmButton = { TextButton(onClick = { showColors = false }) { Text(stringResource(R.string.action_close)) } },
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
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
private fun DisconnectedBar(state: SessionState.Disconnected, onReconnect: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // Without its permission, Android 17 lets connections to the local network run into a timeout
    // and says nothing. When that is the likely cause, say so and offer the way out.
    val nothingAnswered = state.failure == SshFailure.TIMEOUT || state.failure == SshFailure.UNREACHABLE
    val blocked = remember(state) { nothingAnswered && SessionManager.isLocalNetworkBlocked(context) }
    val askForLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> onReconnect()
            // Android stops showing its dialog after it was declined twice. From then on the
            // permission can only be given on the app's page in the system settings.
            activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, SessionManager.LOCAL_NETWORK_PERMISSION) ->
                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }
    DisconnectedBarContent(
        state = state,
        localNetworkBlocked = blocked,
        onAllowLocalNetwork = { askForLocalNetwork.launch(SessionManager.LOCAL_NETWORK_PERMISSION) },
        onReconnect = onReconnect,
        onClose = onClose,
        modifier = modifier,
    )
}

/** What [DisconnectedBar] shows. [localNetworkBlocked] adds the explanation and the button for it. */
@Composable
internal fun DisconnectedBarContent(
    state: SessionState.Disconnected,
    localNetworkBlocked: Boolean,
    onAllowLocalNetwork: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            val hint = if (localNetworkBlocked) R.string.terminal_hint_local_network_blocked else hintFor(state.failure)
            if (hint != null) {
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Buttons that do not fit side by side on a narrow screen move to a second line; a
            // plain row would squeeze the last one until its label broke apart.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close), softWrap = false) }
                if (localNetworkBlocked) {
                    TextButton(onClick = onReconnect) { Text(stringResource(R.string.terminal_reconnect), softWrap = false) }
                    Button(onClick = onAllowLocalNetwork) { Text(stringResource(R.string.terminal_allow_local_network), softWrap = false) }
                } else {
                    Button(onClick = onReconnect) { Text(stringResource(R.string.terminal_reconnect), softWrap = false) }
                }
            }
        }
    }
}

/** Shown while a session whose connection broke is getting it back by itself. */
@Composable
internal fun ReconnectingBar(state: SessionState.Reconnecting, onTryNow: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(state.reason, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (state.attempt == 1) stringResource(R.string.terminal_reconnecting) else stringResource(R.string.terminal_reconnecting_attempt, state.attempt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onStop) { Text(stringResource(R.string.terminal_reconnect_stop), softWrap = false) }
                Button(onClick = onTryNow) { Text(stringResource(R.string.terminal_reconnect_try_now), softWrap = false) }
            }
        }
    }
}

/**
 * Shown while the server of a Mosh session has not been heard from for [silentForMs]. There is
 * nothing to do about it and nothing is lost: the session goes on when the server answers again.
 */
@Composable
internal fun MoshSilentBar(silentForMs: Long, modifier: Modifier = Modifier) {
    val seconds = (silentForMs / 1000).toInt().coerceAtLeast(1)
    Surface(modifier.fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                // Past two minutes the seconds no longer matter.
                if (seconds < 120) pluralStringResource(R.plurals.terminal_mosh_silent_seconds, seconds, seconds)
                else pluralStringResource(R.plurals.terminal_mosh_silent_minutes, seconds / 60, seconds / 60),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.terminal_mosh_silent_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A suggestion for what the user could try, for the failures where there is an obvious one. */
@StringRes
private fun hintFor(failure: SshFailure?): Int? = when (failure) {
    SshFailure.TIMEOUT, SshFailure.UNREACHABLE -> R.string.terminal_hint_no_answer
    SshFailure.REFUSED -> R.string.terminal_hint_refused
    SshFailure.UNKNOWN_HOST -> R.string.terminal_hint_unknown_host
    SshFailure.PROTOCOL -> R.string.terminal_hint_protocol
    SshFailure.AUTH_FAILED -> R.string.terminal_hint_auth_failed
    else -> null
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
                stringResource(
                    when (request.status) {
                        HostKeyStatus.CHANGED -> R.string.terminal_hostkey_changed_title
                        HostKeyStatus.NEW_KEY_TYPE -> R.string.terminal_hostkey_new_type_title
                        else -> R.string.terminal_hostkey_unknown_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (request.status) {
                        HostKeyStatus.CHANGED -> stringResource(R.string.terminal_hostkey_changed_text, request.host)
                        HostKeyStatus.NEW_KEY_TYPE -> stringResource(R.string.terminal_hostkey_new_type_text, request.host)
                        // An address with its port is one thing; there is nothing in it for a translation to arrange.
                        else -> stringResource(R.string.terminal_hostkey_unknown_text, "${request.host}:${request.port}")
                    },
                )
                Text(request.key.type, style = MaterialTheme.typography.labelLarge)
                Text(request.key.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (changed) {
                    for (old in request.knownKeys.filter { it.type == request.key.type }) {
                        Text(stringResource(R.string.terminal_hostkey_previously_trusted), style = MaterialTheme.typography.labelLarge)
                        Text(old.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { prompt.respond(HostKeyDecision.ACCEPT_AND_REMEMBER) }) {
                Text(stringResource(if (changed) R.string.terminal_hostkey_replace else R.string.terminal_hostkey_trust))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { prompt.respond(HostKeyDecision.REJECT) }) { Text(stringResource(R.string.action_cancel)) }
                if (!changed) {
                    TextButton(onClick = { prompt.respond(HostKeyDecision.ACCEPT_ONCE) }) { Text(stringResource(R.string.terminal_hostkey_once)) }
                }
            }
        },
    )
}

@Composable
private fun PasswordDialog(prompt: SessionPrompt.Password) {
    val password = remember(prompt) { TextFieldState() }
    AlertDialog(
        onDismissRequest = { prompt.respond(null) },
        title = { Text(stringResource(R.string.terminal_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // "user@host" is one thing; there is nothing in it for a translation to arrange.
                val account = "${prompt.target.username}@${prompt.target.host}"
                Text(stringResource(if (prompt.attempt > 1) R.string.terminal_password_again else R.string.terminal_password_for, account))
                SecretField(password, stringResource(R.string.terminal_password_label), Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { prompt.respond(password.text.toString().toCharArray()) }) { Text(stringResource(R.string.terminal_log_in)) }
        },
        dismissButton = { TextButton(onClick = { prompt.respond(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A program on the server asks to use one of the user's keys through the forwarded agent. */
@Composable
internal fun AgentUseDialog(prompt: SessionPrompt.AgentUse) {
    AlertDialog(
        onDismissRequest = { prompt.respond(AgentAnswer.DENY) },
        icon = { AppIcon(R.drawable.ic_key, null) },
        title = { Text(stringResource(R.string.terminal_agent_title)) },
        text = { Text(stringResource(R.string.terminal_agent_text, prompt.target.host, prompt.keyName)) },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { prompt.respond(AgentAnswer.ONCE) }) { Text(stringResource(R.string.terminal_agent_allow_once), softWrap = false) }
                TextButton(onClick = { prompt.respond(AgentAnswer.SESSION) }) {
                    Text(stringResource(R.string.terminal_agent_allow_session), softWrap = false)
                }
            }
        },
        dismissButton = { TextButton(onClick = { prompt.respond(AgentAnswer.DENY) }) { Text(stringResource(R.string.terminal_agent_deny)) } },
    )
}

@Composable
private fun ChallengeDialog(prompt: SessionPrompt.Challenge) {
    val challenge = prompt.challenge
    val question = challenge.prompts.first()
    val answer = remember(prompt) { TextFieldState() }
    AlertDialog(
        onDismissRequest = { prompt.respond(null) },
        title = { Text(challenge.name.ifBlank { stringResource(R.string.terminal_challenge_title) }) },
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
        confirmButton = {
            TextButton(onClick = { prompt.respond(listOf(answer.text.toString())) }) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = { TextButton(onClick = { prompt.respond(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
