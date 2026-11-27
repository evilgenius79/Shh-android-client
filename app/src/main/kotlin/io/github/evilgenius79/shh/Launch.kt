package io.github.evilgenius79.shh

import android.content.Intent
import android.net.Uri

/** Something the app was started, or brought back to the front, to do. */
sealed interface Launch {
    /** Show an open session: the user tapped its notification. */
    data class ShowSession(val sessionId: String) : Launch

    /** Connect to a saved host: the user tapped its shortcut on the home screen. */
    data class Connect(val hostId: String) : Launch

    /**
     * Offer to connect to [address]: the user followed an ssh:// link. Nothing connects until
     * they confirm, because a link can come from anywhere.
     */
    data class Offer(val address: String) : Launch

    companion object {
        const val ACTION_SHOW_SESSION = "io.github.evilgenius79.shh.SHOW_SESSION"
        const val ACTION_CONNECT = "io.github.evilgenius79.shh.CONNECT"
        const val EXTRA_ID = "id"

        fun from(intent: Intent?): Launch? = when (intent?.action) {
            ACTION_SHOW_SESSION -> intent.getStringExtra(EXTRA_ID)?.let { ShowSession(it) }
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_ID)?.let { Connect(it) }
            Intent.ACTION_VIEW -> intent.data?.let { addressOf(it) }?.let { Offer(it) }
            else -> null
        }

        /**
         * `user@host:port` for an ssh:// link, or null when it is not one. A password or a path
         * in the link is dropped: neither is something to take from a link.
         */
        fun addressOf(link: Uri): String? {
            if (!"ssh".equals(link.scheme, ignoreCase = true)) return null
            val host = link.host?.takeIf { it.isNotBlank() } ?: return null
            val user = link.userInfo?.substringBefore(':')?.takeIf { it.isNotEmpty() }
            return buildString {
                if (user != null) append(user).append('@')
                // An IPv6 address needs its brackets to keep its colons apart from the port's.
                if (host.contains(':') && !host.startsWith("[")) append('[').append(host).append(']') else append(host)
                if (link.port > 0) append(':').append(link.port)
            }
        }
    }
}
