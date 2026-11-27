package io.github.evilgenius79.shh.data

import kotlinx.serialization.Serializable

/** A saved server. */
@Serializable
data class Host(
    val id: String,
    /** Display name; when blank the address is shown instead. */
    val name: String = "",
    val host: String,
    val port: Int = 22,
    val username: String = "",
    /** Free-form label used to group hosts in the list. */
    val group: String = "",
    /** Key to log in with, see [Identity.id]. */
    val identityId: String? = null,
    /** Password encrypted with [SecretBox]; null when none is saved. */
    val encryptedPassword: String? = null,
    /** Another saved host to connect through, see [Host.id]. */
    val jumpHostId: String? = null,
    /** Sent to the shell right after login, for example `tmux new -A -s main`. */
    val startupCommand: String = "",
    val forwards: List<ForwardRule> = emptyList(),
    val allowLegacyAlgorithms: Boolean = false,
    val compression: Boolean = false,
    val keepAliveSeconds: Int = 30,
    /** Colour scheme for this host, or null to use the default from the settings. */
    val colorSchemeId: String? = null,
    val termType: String = "xterm-256color",
    val lastConnectedAt: Long = 0,
    /** Which of the name's addresses to use when it has both kinds. */
    val internetProtocol: InternetProtocol = InternetProtocol.AUTOMATIC,
    /** Lend the keys stored in the app to programs on this server (agent forwarding). */
    val forwardAgent: Boolean = false,
) {
    /** `user@host` plus the port when it is not 22. */
    val address: String
        get() {
            val user = if (username.isBlank()) "" else "$username@"
            val suffix = if (port == 22) "" else ":$port"
            return "$user$host$suffix"
        }

    val title: String get() = name.ifBlank { address }
}

@Serializable
enum class InternetProtocol { AUTOMATIC, IPV4, IPV6 }

@Serializable
enum class ForwardKind { LOCAL, REMOTE, DYNAMIC }

/** A port forward that belongs to a host and is set up when its session connects. */
@Serializable
data class ForwardRule(
    val id: String,
    val kind: ForwardKind,
    val bindAddress: String = "127.0.0.1",
    val bindPort: Int,
    /** Unused for [ForwardKind.DYNAMIC]. */
    val destinationHost: String = "",
    val destinationPort: Int = 0,
    val enabled: Boolean = true,
) {
    /** The same notation `ssh` uses on its command line. */
    val summary: String
        get() = when (kind) {
            ForwardKind.LOCAL -> "-L $bindAddress:$bindPort:$destinationHost:$destinationPort"
            ForwardKind.REMOTE -> "-R $bindAddress:$bindPort:$destinationHost:$destinationPort"
            ForwardKind.DYNAMIC -> "-D $bindAddress:$bindPort"
        }
}

/** A key pair the user can log in with. The private half is stored encrypted. */
@Serializable
data class Identity(
    val id: String,
    val name: String,
    /** Human readable algorithm, for example "Ed25519" or "RSA 4096". */
    val algorithm: String,
    /** `authorized_keys` line without a comment: key type and base64 key. */
    val publicKey: String,
    val fingerprint: String,
    /** The private key in OpenSSH format, encrypted with [SecretBox]. */
    val encryptedPrivateKey: String,
    val createdAt: Long = 0,
    /** A certificate for this key (the line of a `-cert.pub` file), offered before the key itself. */
    val certificate: String? = null,
)

/** A server key the user has accepted. */
@Serializable
data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    val keyBase64: String,
    val addedAt: Long = 0,
)

/** A saved command that can be sent to a terminal with one tap. */
@Serializable
data class Snippet(
    val id: String,
    val name: String,
    val command: String,
    /** Press Enter after pasting the command. */
    val runImmediately: Boolean = true,
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class CursorStyle { BLOCK, UNDERLINE, BAR }

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Use the wallpaper based Material You palette where the system offers one. */
    val dynamicColor: Boolean = true,
    val colorSchemeId: String = "default-dark",
    val fontSizeSp: Int = 13,
    /** The stored font file the terminal draws with, see TerminalFonts. Null for the bundled typeface. */
    val terminalFont: String? = null,
    /** What that font is called, for showing it in the settings. */
    val terminalFontName: String? = null,
    val scrollbackLines: Int = 5000,
    val keepScreenOn: Boolean = true,
    /** Hold a wake lock while sessions are connected so they survive the screen turning off. */
    val keepAwake: Boolean = true,
    val vibrateOnBell: Boolean = true,
    val cursorStyle: CursorStyle = CursorStyle.BLOCK,
    val cursorBlink: Boolean = true,
    val showExtraKeys: Boolean = true,
    /** The sixteen keys of the bar, upper row first. Empty means the standard keys. */
    val keyBar: List<String> = emptyList(),
    /** Leave the bar out while a hardware keyboard is connected. */
    val hideKeysWithKeyboard: Boolean = false,
    /** Get a connection that broke back without being asked. */
    val autoReconnect: Boolean = true,
    /** Present the terminal to the keyboard as ordinary text: voice, swipe and CJK input work. */
    val fullKeyboard: Boolean = false,
    /** Close a session's tab when the user leaves its shell ("exit", Ctrl+D). */
    val closeOnExit: Boolean = true,
    /** Post a notification when a session that is not on screen rings the bell. */
    val notifyOnBell: Boolean = true,
    /** Ask for the screen lock (fingerprint, PIN, ...) when the app is opened. */
    val requireUnlock: Boolean = false,
    /** Let programs on the server put text on the clipboard (OSC 52). */
    val allowRemoteClipboard: Boolean = false,
)

/** Everything the app persists, kept in one JSON document. */
@Serializable
data class AppData(
    val version: Int = CURRENT_VERSION,
    val hosts: List<Host> = emptyList(),
    val identities: List<Identity> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val settings: Settings = Settings(),
) {
    fun host(id: String?): Host? = hosts.firstOrNull { it.id == id }

    fun identity(id: String?): Identity? = identities.firstOrNull { it.id == id }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
