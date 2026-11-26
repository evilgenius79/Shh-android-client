package io.github.evilgenius79.shh.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.evilgenius79.shh.AppContainer
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.session.SessionManager

/** The whole UI: picks the screen to show and connects the screens to the app's state. */
@Composable
fun ShhRoot(container: AppContainer, model: AppViewModel = viewModel()) {
    val data by container.store.data.collectAsStateCompat()
    val sessions by container.sessions.sessions.collectAsStateCompat()
    val context = LocalContext.current

    ShhTheme(data.settings) {
        if (data.settings.requireUnlock && !model.unlocked) {
            LockScreen(onUnlocked = { model.unlocked = true })
            return@ShhTheme
        }

        BackHandler(enabled = model.canGoBack) { model.back() }

        // Connecting needs up to two runtime permissions. They are requested right before the
        // first connection, when it is obvious why, and the connection goes ahead either way.
        var pendingHost by remember { mutableStateOf<Host?>(null) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingHost?.let { host ->
                pendingHost = null
                model.showSession(container.sessions.open(host).id)
            }
        }

        fun connect(host: Host) {
            val wanted = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            if (SessionManager.needsLocalNetworkPermission) wanted.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            val missing = wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                model.showSession(container.sessions.open(host).id)
            } else {
                pendingHost = host
                permissionLauncher.launch(missing.toTypedArray())
            }
        }

        // If the session being shown was closed, fall back to another one or to the host list.
        val active = container.sessions.session(model.activeSessionId) ?: sessions.lastOrNull()
        LaunchedEffect(active, model.current) {
            if (model.current == Screen.Terminal && active == null) model.back()
            val files = model.current as? Screen.Files
            if (files != null && container.sessions.session(files.sessionId) == null) model.back()
        }

        when (val screen = model.current) {
            Screen.Hosts -> HostsScreen(
                data = data,
                sessions = sessions,
                onConnect = ::connect,
                onQuickConnect = { host -> connect(host.copy(id = AppStore.newId())) },
                onOpenSession = { model.showSession(it.id) },
                onEdit = { model.open(Screen.HostEdit(it.id)) },
                onDuplicate = { model.open(Screen.HostEdit(null, templateId = it.id)) },
                onDelete = { container.store.deleteHost(it.id) },
                onAdd = { model.open(Screen.HostEdit(null)) },
                onNavigate = model::open,
            )
            is Screen.HostEdit -> {
                val existing = data.host(screen.hostId)
                val template = data.host(screen.templateId)
                val original = remember(screen) {
                    existing
                        ?: template?.copy(id = AppStore.newId(), name = template.title + " (copy)", lastConnectedAt = 0)
                        ?: Host(id = AppStore.newId(), host = "")
                }
                HostEditScreen(
                    data = data,
                    original = original,
                    isNew = existing == null,
                    hasSavedPassword = original.encryptedPassword != null,
                    onSave = { host, password ->
                        val encrypted = when {
                            password == null -> host.encryptedPassword
                            password.isEmpty() -> null
                            else -> container.secrets.encryptText(password)
                        }
                        container.store.saveHost(host.copy(encryptedPassword = encrypted))
                        model.back()
                    },
                    onBack = model::back,
                )
            }
            Screen.Terminal -> if (active != null) {
                TerminalScreen(
                    sessions = sessions,
                    active = active,
                    settings = data.settings,
                    snippets = data.snippets,
                    identities = data.identities,
                    onSelect = { model.activeSessionId = it.id },
                    onClose = { container.sessions.close(it) },
                    onNewSession = { model.open(Screen.Hosts) },
                    onOpenFiles = { model.open(Screen.Files(it.id)) },
                    onFontSize = { size -> container.store.updateSettings { it.copy(fontSizeSp = size) } },
                    onBack = model::back,
                )
            }
            is Screen.Files -> container.sessions.session(screen.sessionId)?.let { session ->
                FilesScreen(session = session, onBack = model::back)
            }
            Screen.Keys -> KeysScreen(container = container, data = data, onBack = model::back)
            Screen.KnownHosts -> KnownHostsScreen(data = data, onForget = { container.store.forgetHostKey(it) }, onBack = model::back)
            Screen.Snippets -> SnippetsScreen(
                snippets = data.snippets,
                onSave = { container.store.saveSnippet(it) },
                onDelete = { container.store.deleteSnippet(it.id) },
                onBack = model::back,
            )
            Screen.Settings -> SettingsScreen(container = container, data = data, onBack = model::back)
            Screen.About -> AboutScreen(onBack = model::back)
        }
    }
}
