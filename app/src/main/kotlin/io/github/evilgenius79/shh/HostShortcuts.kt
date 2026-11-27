package io.github.evilgenius79.shh

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.Host

/**
 * The shortcuts under the app's icon on the home screen: the hosts connected to most recently,
 * one tap away.
 */
object HostShortcuts {
    /** Launchers show about four. */
    private const val COUNT = 4

    /** The hosts that get a shortcut, most recently used first. */
    fun choose(data: AppData): List<Host> =
        data.hosts.filter { it.lastConnectedAt > 0 }.sortedByDescending { it.lastConnectedAt }.take(COUNT)

    fun publish(context: Context, hosts: List<Host>) {
        val shortcuts = hosts.mapIndexed { rank, host ->
            ShortcutInfoCompat.Builder(context, host.id)
                .setShortLabel(host.title)
                .setLongLabel(context.getString(R.string.shortcut_connect_to, host.title))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_host))
                .setRank(rank)
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Launch.ACTION_CONNECT)
                        .putExtra(Launch.EXTRA_ID, host.id),
                )
                .build()
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (_: RuntimeException) {
            // Launchers limit how often shortcuts may change; the next change gets through.
        }
    }
}
