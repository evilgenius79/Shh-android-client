package io.github.evilgenius79.shh

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.session.SessionManager
import io.github.evilgenius79.shh.ssh.InMemoryHostKeyStore
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ways into the app other than its icon: shortcuts, links and notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LaunchAndNotifyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ----- what an intent asks for -----

    @Test
    fun intentsAreReadAsWhatTheyAskFor() {
        assertEquals(Launch.ShowSession("s1"), Launch.from(Intent(Launch.ACTION_SHOW_SESSION).putExtra(Launch.EXTRA_ID, "s1")))
        assertEquals(Launch.Connect("h7"), Launch.from(Intent(Launch.ACTION_CONNECT).putExtra(Launch.EXTRA_ID, "h7")))
        assertEquals(Launch.Offer("root@example.org:2222"), Launch.from(Intent(Intent.ACTION_VIEW, Uri.parse("ssh://root@example.org:2222"))))
        // Starting the app from its icon asks for nothing, and neither does anything unknown.
        assertNull(Launch.from(Intent(Intent.ACTION_MAIN)))
        assertNull(Launch.from(Intent(Launch.ACTION_CONNECT)))
        assertNull(Launch.from(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))))
        assertNull(Launch.from(null))
    }

    @Test
    fun anSshLinkBecomesAnAddressAndNothingMore() {
        fun address(link: String) = Launch.addressOf(Uri.parse(link))
        assertEquals("example.org", address("ssh://example.org"))
        assertEquals("pi@raspberrypi.local", address("ssh://pi@raspberrypi.local"))
        assertEquals("admin@10.0.0.1:2200", address("SSH://admin@10.0.0.1:2200"))
        // A password in a link is not taken, and a path means nothing to a terminal.
        assertEquals("admin@example.org", address("ssh://admin:hunter2@example.org/etc/passwd"))
        assertEquals("root@[fe80::1]:22", address("ssh://root@[fe80::1]:22"))
        assertNull(address("ssh://"))
        assertNull(address("telnet://example.org"))
        // The host list understands every address this produces.
        for (link in listOf("ssh://pi@raspberrypi.local", "ssh://admin@10.0.0.1:2200", "ssh://root@[fe80::1]:22")) {
            val host = io.github.evilgenius79.shh.ui.parseQuickConnect(address(link)!!)
            assertEquals(Uri.parse(link).userInfo, host?.username, link)
        }
    }

    // ----- shortcuts -----

    @Test
    fun theHomeScreenShortcutsAreTheHostsUsedLast() {
        val hosts = listOf(
            Host(id = "a", name = "Alpha", host = "a.example", username = "u", lastConnectedAt = 100),
            Host(id = "b", name = "Never used", host = "b.example", username = "u"),
            Host(id = "c", host = "c.example", username = "root", lastConnectedAt = 300),
            Host(id = "d", name = "Delta", host = "d.example", username = "u", lastConnectedAt = 200),
            Host(id = "e", name = "Echo", host = "e.example", username = "u", lastConnectedAt = 50),
            Host(id = "f", name = "Foxtrot", host = "f.example", username = "u", lastConnectedAt = 10),
        )
        val chosen = HostShortcuts.choose(AppData(hosts = hosts))
        assertEquals(listOf("c", "d", "a", "e"), chosen.map { it.id }, "the four used most recently, newest first")

        HostShortcuts.publish(context, chosen)
        val published = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }
        assertEquals(listOf("c", "d", "a", "e"), published.map { it.id })
        assertEquals("root@c.example", published[0].shortLabel.toString(), "a host without a name is called by its address")
        assertEquals("Connect to Delta", published[1].longLabel.toString())
        // Tapping one asks the app to connect to exactly that host.
        assertEquals(Launch.Connect("d"), Launch.from(published[1].intent))

        // A host that is deleted loses its shortcut with the next update.
        HostShortcuts.publish(context, HostShortcuts.choose(AppData(hosts = hosts.filter { it.id != "c" })))
        assertEquals(listOf("d", "a", "e", "f"), ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }.map { it.id })
    }

    // ----- the bell -----

    private fun manager(data: AppData = AppData()): Pair<AppStore, SessionManager> {
        val store = AppStore(File(temp.root, "store-${System.nanoTime()}.json"), Executor { it.run() })
        store.replaceAll(data)
        return store to SessionManager(context, store, TestSecretBox(), InMemoryHostKeyStore())
    }

    private fun bellNotifications(): List<android.app.Notification> {
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
    }

    // Nothing listens on port 1: the sessions end at once, which does not matter to a bell.
    private val buildBox = Host(id = "h1", name = "Build box", host = "127.0.0.1", port = 1, username = "u")
    private val other = Host(id = "h2", name = "Other", host = "127.0.0.1", port = 1, username = "u")

    @Test
    fun aBellInASessionNobodyIsLookingAtBecomesANotification() {
        val (_, sessions) = manager()
        val session = sessions.open(buildBox)
        assertEquals(emptyList(), bellNotifications())

        // The app is in the background.
        session.onBell()
        val posted = bellNotifications().single()
        assertEquals("Build box", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(Launch.ShowSession(session.id), Launch.from(shadowOf(posted.contentIntent).savedIntent), "tapping it opens that session")

        // A program that keeps ringing does not pile them up.
        session.onBell()
        session.onBell()
        assertEquals(1, bellNotifications().size)

        // Looking at the session answers the bell.
        sessions.appInForeground = true
        sessions.visibleSessionId = session.id
        assertEquals(emptyList(), bellNotifications())
        session.onBell()
        assertEquals(emptyList(), bellNotifications(), "and while it is on screen a bell needs no notification")

        // Another session in the background still gets one, also while the app is open.
        val second = sessions.open(other)
        second.onBell()
        assertEquals("Other", bellNotifications().single().extras.getString(android.app.Notification.EXTRA_TITLE))

        // Closing a session takes its notification along.
        sessions.close(second)
        assertEquals(emptyList(), bellNotifications())
    }

    @Test
    fun theBellNotificationCanBeSwitchedOff() {
        val (store, sessions) = manager()
        store.updateSettings { it.copy(notifyOnBell = false) }
        sessions.open(buildBox).onBell()
        assertEquals(emptyList(), bellNotifications())
    }
}
