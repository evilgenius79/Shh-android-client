package io.github.evilgenius79.shh.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.SecretBox
import io.github.evilgenius79.shh.ssh.HostKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns every open [TerminalSession] and keeps the foreground service in step with them. */
class SessionManager(
    private val context: Context,
    private val store: AppStore,
    secrets: SecretBox,
    hostKeys: HostKeyStore,
) {
    private val main = Handler(Looper.getMainLooper())
    private val sessionsFlow = MutableStateFlow<List<TerminalSession>>(emptyList())

    /** Open sessions in tab order. */
    val sessions: StateFlow<List<TerminalSession>> = sessionsFlow.asStateFlow()

    private val environment = SessionEnvironment(
        data = { store.data.value },
        secrets = secrets,
        hostKeys = hostKeys,
        onBell = { main.post { bell() } },
        onClipboard = { text -> main.post { copyToClipboard(text) } },
        onConnected = { host ->
            store.update { data ->
                data.copy(hosts = data.hosts.map { if (it.id == host.id) it.copy(lastConnectedAt = System.currentTimeMillis()) else it })
            }
        },
    )

    fun session(id: String?): TerminalSession? = sessionsFlow.value.firstOrNull { it.id == id }

    /** Opens a new session to [host] and starts connecting. */
    fun open(host: Host): TerminalSession {
        val session = TerminalSession(AppStore.newId(), host, environment)
        sessionsFlow.value = sessionsFlow.value + session
        updateService()
        session.connect()
        return session
    }

    fun close(session: TerminalSession) {
        session.close()
        sessionsFlow.value = sessionsFlow.value - session
        updateService()
    }

    fun closeAll() {
        val all = sessionsFlow.value
        sessionsFlow.value = emptyList()
        for (session in all) session.close()
        updateService()
    }

    private fun updateService() {
        val intent = Intent(context, SessionService::class.java)
        if (sessionsFlow.value.isEmpty()) {
            context.stopService(intent)
        } else {
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun bell() {
        if (!store.data.value.settings.vibrateOnBell) return
        val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun copyToClipboard(text: String) {
        val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Shh", text))
    }

    companion object {
        /** True on Android versions that put local network access behind a runtime permission. */
        val needsLocalNetworkPermission: Boolean get() = Build.VERSION.SDK_INT >= 37
    }
}
