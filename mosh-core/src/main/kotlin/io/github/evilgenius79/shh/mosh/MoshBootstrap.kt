package io.github.evilgenius79.shh.mosh

/**
 * The part of starting a Mosh session that happens before any UDP is sent: getting a
 * `mosh-server` running on the remote host and learning where it listens and with which key.
 * That is done over some other, already authenticated channel, normally by running a command
 * over SSH.
 */
public object MoshBootstrap {
    private val LOCALE = Regex("[A-Za-z0-9_.@-]+")
    private val UDP_PORTS = Regex("[0-9]{1,5}(:[0-9]{1,5})?")
    private val CONNECT = Regex("(?:^|[\\r\\n])MOSH CONNECT ([0-9]{1,5}) ([A-Za-z0-9/+]{22})[ \\t]*(?=$|[\\r\\n])")

    private const val FALLBACK_LOCALE = "C.UTF-8"

    /**
     * The command line to run on the remote host (through the user's login shell, as an SSH
     * "exec" request does) to start a server, for example
     * `mosh-server new -s -c 256 -l LANG=en_US.UTF-8 || mosh-server new -s -c 256 -l LANG=C.UTF-8`.
     *
     * The server prints a `MOSH CONNECT` line for [parseConnect] on its standard output, detaches
     * and the command returns; the server itself stays behind, waiting up to a minute for the
     * client's first datagram. No terminal should be requested for the command.
     *
     * `-s` makes the server listen on the address the SSH connection came in on, `-c 256` gives
     * the remote program a 256 colour terminal (`TERM=xterm-256color`).
     *
     * mosh-server refuses to start unless it ends up in a UTF-8 locale. It keeps the remote
     * account's own locale if that is one; otherwise it tries the one named by [locale], and
     * that only works if the remote system has it installed. Which names exist differs: minimal
     * Linux installations often have nothing but `C.UTF-8`, other systems do not know that name
     * at all. So when [locale] is something else, the command tries again with `C.UTF-8` if the
     * first attempt fails.
     *
     * @param locale value for `LANG` on the remote side if its own locale is not UTF-8
     * @param udpPorts UDP port, or range `low:high`, for the server to choose from; by default
     *   it takes the first free port from 60001 to 60999
     * @throws IllegalArgumentException if an argument is not of the expected form (they end up
     *   on a shell command line)
     */
    public fun serverCommand(locale: String = "en_US.UTF-8", udpPorts: String? = null): String {
        require(LOCALE.matches(locale)) { "Not a locale name: $locale" }
        require(udpPorts == null || UDP_PORTS.matches(udpPorts)) { "Not a UDP port or port range: $udpPorts" }
        val ports = if (udpPorts != null) " -p $udpPorts" else ""
        val first = "mosh-server new -s -c 256 -l LANG=$locale$ports"
        if (locale.equals(FALLBACK_LOCALE, ignoreCase = true)) return first
        return "$first || mosh-server new -s -c 256 -l LANG=$FALLBACK_LOCALE$ports"
    }

    /**
     * Port and key from the server's `MOSH CONNECT <port> <key>` line anywhere in [output], or
     * null if there is none (yet).
     *
     * The line may be surrounded by anything else the remote side printed, such as a login
     * banner before it and the server's licence notice after it, and may end in CR LF. If there
     * are several such lines the last one counts: whatever came earlier was printed by the
     * remote account's startup files, not by the server.
     */
    public fun parseConnect(output: String): Pair<Int, String>? {
        val match = CONNECT.findAll(output).lastOrNull() ?: return null
        val port = match.groupValues[1].toInt()
        if (port !in 1..65535) return null
        val key = match.groupValues[2]
        // Twenty-two base64 characters hold four bits more than a key; a real key has them clear.
        try {
            PacketCodec.decodeKey(key)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return port to key
    }
}
