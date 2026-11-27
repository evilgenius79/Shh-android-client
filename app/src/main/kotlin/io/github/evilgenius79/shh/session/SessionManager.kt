package io.github.evilgenius79.shh.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
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

    init {
        // A session that lost its connection waits a while before each new attempt. When the
        // network comes back there is no reason to wait any longer.
        try {
            ContextCompat.getSystemService(context, ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        for (session in sessionsFlow.value) session.retryNow()
                    }
                },
            )
        } catch (_: RuntimeException) {
            // Without the callback the sessions still retry, only not as promptly.
        }
    }

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
        /**
         * The runtime permission Android 17 (API 37) introduced for reaching the local network.
         * Spelled out because the platform's constant for it does not exist on older versions.
         */
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** True on Android versions that put local network access behind a runtime permission. */
        val needsLocalNetworkPermission: Boolean get() = Build.VERSION.SDK_INT >= 37

        /**
         * True when Android is withholding the local network from this app. The system then drops
         * the app's packets to local addresses without an error, so that a connection attempt
         * simply runs into its timeout.
         */
        fun isLocalNetworkBlocked(context: Context): Boolean =
            needsLocalNetworkPermission &&
                ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
    }
}
