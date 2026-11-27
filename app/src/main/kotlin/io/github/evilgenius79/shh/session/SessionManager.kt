package io.github.evilgenius79.shh.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import io.github.evilgenius79.shh.Launch
import io.github.evilgenius79.shh.MainActivity
import io.github.evilgenius79.shh.R
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
        onBell = { session -> main.post { bell(session) } },
        onShellExited = { session, byUser ->
            // Leaving the shell with "exit" or Ctrl+D is how a session is closed from the inside.
            // A shell that ends on its own stays, so that the reason can still be read.
            if (byUser && store.data.value.settings.closeOnExit) main.post { close(session) }
        },
        onClipboard = { text -> main.post { copyToClipboard(text) } },
        onConnected = { host ->
            store.update { data ->
                data.copy(hosts = data.hosts.map { if (it.id == host.id) it.copy(lastConnectedAt = System.currentTimeMillis()) else it })
            }
            // Lets the launcher learn which of the home screen shortcuts matter.
            try {
                ShortcutManagerCompat.reportShortcutUsed(context, host.id)
            } catch (_: RuntimeException) {
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
        notifications()?.cancel(bellNotificationId(session))
        updateService()
    }

    fun closeAll() {
        val all = sessionsFlow.value
        sessionsFlow.value = emptyList()
        for (session in all) {
            session.close()
            notifications()?.cancel(bellNotificationId(session))
        }
        updateService()
    }

    /** True while the app has an activity on screen. Set by the application. */
    @Volatile
    var appInForeground: Boolean = false
        set(value) {
            field = value
            if (value) answerBell()
        }

    /** The session whose terminal is showing, if one is. Set by the UI. */
    @Volatile
    var visibleSessionId: String? = null
        set(value) {
            field = value
            answerBell()
        }

    /**
     * Looking at a session answers its bell: its notification goes away, whether the user got
     * there by switching tabs or by coming back to the app.
     */
    private fun answerBell() {
        if (!appInForeground) return
        session(visibleSessionId)?.let { notifications()?.cancel(bellNotificationId(it)) }
    }

    private fun isInView(session: TerminalSession) = appInForeground && visibleSessionId == session.id

    private fun updateService() {
        val intent = Intent(context, SessionService::class.java)
        if (sessionsFlow.value.isEmpty()) {
            context.stopService(intent)
        } else {
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun bell(session: TerminalSession) {
        val settings = store.data.value.settings
        if (settings.notifyOnBell && !isInView(session) && session in sessionsFlow.value) notifyBell(session)
        if (!settings.vibrateOnBell) return
        val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun notifications(): NotificationManager? = ContextCompat.getSystemService(context, NotificationManager::class.java)

    private fun bellNotificationId(session: TerminalSession): Int = BELL_NOTIFICATION_BASE + (session.id.hashCode() and 0xFFFF)

    /**
     * A program in a session nobody is looking at rang the bell, which is how a long job says
     * that it is done ("make; printf '\\a'"). Tapping the notification opens that session.
     */
    private fun notifyBell(session: TerminalSession) {
        val manager = notifications() ?: return
        val channel = NotificationChannel(BELL_CHANNEL_ID, context.getString(R.string.notify_bell_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notify_bell_channel_description)
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            context,
            bellNotificationId(session),
            Intent(context, MainActivity::class.java).setAction(Launch.ACTION_SHOW_SESSION).putExtra(Launch.EXTRA_ID, session.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, BELL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(session.title.value)
            .setContentText(context.getString(R.string.notify_bell_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            // A program that keeps ringing does not keep buzzing the phone.
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(bellNotificationId(session), notification)
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

        private const val BELL_CHANNEL_ID = "bell"
        private const val BELL_NOTIFICATION_BASE = 1000

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
