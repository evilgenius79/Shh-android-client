package io.github.evilgenius79.shh.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.evilgenius79.shh.MainActivity
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.ShhApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process, and with it every SSH connection, alive while the app is in the background.
 * It runs for exactly as long as there is at least one open session and shows how many there are.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ShhApp
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            app.container.sessions.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(app.container.sessions.sessions.value.size)
        if (watcher == null) {
            watcher = scope.launch {
                app.container.sessions.sessions.collectLatest { sessions ->
                    if (sessions.isEmpty()) {
                        stopSelf()
                    } else {
                        notificationManager().notify(NOTIFICATION_ID, buildNotification(sessions.size))
                        updateWakeLock(app.container.store.data.value.settings.keepAwake)
                    }
                }
            }
        }
        // If the system kills the process the connections are gone anyway; nothing to restart.
        return START_NOT_STICKY
    }

    private fun startInForeground(count: Int) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(count), type)
    }

    private fun updateWakeLock(wanted: Boolean) {
        if (wanted && wakeLock == null) {
            val power = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shh:sessions").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!wanted) {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_sessions), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.channel_sessions_description)
        channel.setShowBadge(false)
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(resources.getQuantityString(R.plurals.notification_sessions, count, count))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_power_settings_new, getString(R.string.action_disconnect_all), disconnect)
            .build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISCONNECT_ALL = "io.github.evilgenius79.shh.DISCONNECT_ALL"
        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1
    }
}
