package io.github.evilgenius79.shh.ssh

/** One hop of a `ProxyJump`: `[user@]host[:port]`. */
data class ConfigJump(val host: String, val user: String? = null, val port: Int? = null)

/**
 * A `LocalForward`, `RemoteForward` or `DynamicForward` line. [bindAddress] is null where the
 * file names none; [host] and [hostPort] are null for a dynamic forward.
 */
data class ConfigForward(val bindAddress: String?, val bindPort: Int, val host: String? = null, val hostPort: Int? = null)

/**
 * What an OpenSSH client configuration says about one host. A property is null (or empty) where
 * the file says nothing, which is where `ssh` would use its default.
 */
data class ConfigHost(
    /** The name the host goes by: what one types after `ssh`. */
    val alias: String,
    /** The address to connect to: `HostName`, or the alias itself. */
    val hostName: String,
    val port: Int? = null,
    val user: String? = null,
    /** The hosts to go through, the first one being the one connected to directly. */
    val jumps: List<ConfigJump> = emptyList(),
    /** A `ProxyCommand` that is not just another way of writing a jump host. */
    val proxyCommand: String? = null,
    val localForwards: List<ConfigForward> = emptyList(),
    val remoteForwards: List<ConfigForward> = emptyList(),
    val dynamicForwards: List<ConfigForward> = emptyList(),
    val forwardAgent: Boolean? = null,
    val addressFamily: AddressFamily? = null,
    val serverAliveInterval: Int? = null,
    val compression: Boolean? = null,
    val remoteCommand: String? = null,
    val identityFiles: List<String> = emptyList(),
    /** The file switches on algorithms for this host that current versions of OpenSSH leave off. */
    val wantsLegacyAlgorithms: Boolean = false,
)

/**
 * An OpenSSH client configuration file (`~/.ssh/config`), read the way `ssh` reads it: for every
 * setting the first value that applies to a host wins, `Host` lines select by pattern, and
 * settings before the first `Host` line apply to every host.
 *
 * Only what can be decided from the file itself is honoured. `Include` is not followed, and a
 * `Match` block applies only when its conditions are `all`, `host` or `originalhost`; the
 * properties below say whether the file has such parts.
 */
class SshConfig private constructor(private val lines: List<Line>) {
    private class Line(val keyword: String, val arguments: List<String>, val rest: String)

    /**
     * The hosts the file names: every pattern of a `Host` line that is a plain name rather than
     * a pattern, in the order of the file.
     */
    val aliases: List<String> = lines
        .filter { it.keyword == "host" }
        .flatMap { it.arguments }
        .filter { !it.startsWith("!") && '*' !in it && '?' !in it }
        .distinct()

    /** The file refers to other files with `Include`. They were not read. */
    val hasIncludes: Boolean = lines.any { it.keyword == "include" }

    /** The file has `Match` blocks with conditions that cannot be checked here. They were left out. */
    val hasUncheckedMatches: Boolean = lines.any { it.keyword == "match" && !isCheckable(it.arguments) }

    /** What the file says about the host one would reach with `ssh name`. */
    fun resolve(name: String): ConfigHost {
        var hostName: String? = null
        var port: Int? = null
        var user: String? = null
        var proxyDecided = false
        var jumps = emptyList<ConfigJump>()
        var proxyCommand: String? = null
        val local = ArrayList<ConfigForward>()
        val remote = ArrayList<ConfigForward>()
        val dynamic = ArrayList<ConfigForward>()
        var forwardAgent: Boolean? = null
        var family: AddressFamily? = null
        var alive: Int? = null
        var compression: Boolean? = null
        var remoteCommand: String? = null
        val identities = ArrayList<String>()
        var legacy = false

        var active = true
        for (line in lines) {
            val first = line.arguments.firstOrNull()
            when (line.keyword) {
                "host" -> active = hostLineMatches(line.arguments, name)
                // Like ssh, a Match on "host" sees the HostName found so far.
                "match" -> active = matchLineMatches(line.arguments, name, hostName?.let { expand(it, name) } ?: name)
                else -> if (active && first != null) {
                    when (line.keyword) {
                        "hostname" -> if (hostName == null) hostName = first
                        "port" -> if (port == null) port = first.toIntOrNull()?.takeIf { it in 1..65535 }
                        "user" -> if (user == null) user = first
                        "proxyjump" -> if (!proxyDecided) {
                            proxyDecided = true
                            jumps = if (first.equals("none", ignoreCase = true)) emptyList() else first.split(',').mapNotNull(::parseJump)
                        }
                        // Whichever of ProxyJump and ProxyCommand comes first decides, as in ssh.
                        "proxycommand" -> if (!proxyDecided) {
                            proxyDecided = true
                            val jump = jumpOf(line.rest)
                            if (jump != null) jumps = listOf(jump) else if (!line.rest.equals("none", ignoreCase = true)) proxyCommand = line.rest
                        }
                        "localforward" -> parseForward(first, line.arguments.getOrNull(1))?.let(local::add)
                        "remoteforward" -> parseForward(first, line.arguments.getOrNull(1))?.let(remote::add)
                        "dynamicforward" -> parseForward(first, null, dynamic = true)?.let(dynamic::add)
                        "forwardagent" -> if (forwardAgent == null) forwardAgent = flag(first)
                        "addressfamily" -> if (family == null) {
                            family = when (first.lowercase()) {
                                "inet" -> AddressFamily.IPV4
                                "inet6" -> AddressFamily.IPV6
                                "any" -> AddressFamily.ANY
                                else -> null
                            }
                        }
                        "serveraliveinterval" -> if (alive == null) alive = first.toIntOrNull()?.takeIf { it >= 0 }
                        "compression" -> if (compression == null) compression = flag(first)
                        "remotecommand" -> if (remoteCommand == null) remoteCommand = line.rest
                        "identityfile" -> if (!first.equals("none", ignoreCase = true)) identities.add(first)
                        in ALGORITHM_LISTS -> if (LEGACY_ALGORITHMS.any { it in first.lowercase() }) legacy = true
                    }
                }
            }
        }
        return ConfigHost(
            alias = name,
            hostName = hostName?.let { expand(it, name) } ?: name,
            port = port,
            user = user,
            jumps = jumps,
            proxyCommand = proxyCommand,
            localForwards = local,
            remoteForwards = remote,
            dynamicForwards = dynamic,
            forwardAgent = forwardAgent,
            addressFamily = family,
            serverAliveInterval = alive,
            compression = compression,
            remoteCommand = remoteCommand?.takeUnless { it.equals("none", ignoreCase = true) },
            identityFiles = identities.distinct(),
            wantsLegacyAlgorithms = legacy,
        )
    }

    private fun hostLineMatches(patterns: List<String>, name: String): Boolean {
        var matched = false
        for (pattern in patterns) {
            if (pattern.startsWith("!")) {
                // A negated pattern that matches rules the whole line out.
                if (glob(pattern.substring(1), name)) return false
            } else if (glob(pattern, name)) {
                matched = true
            }
        }
        return matched
    }

    private fun matchLineMatches(arguments: List<String>, name: String, hostName: String): Boolean {
        if (!isCheckable(arguments)) return false
        if (arguments.size == 1) return true // "all"
        return arguments.chunked(2).all { (criterion, patterns) ->
            val subject = if (criterion.lowercase() == "host") hostName else name
            patternListMatches(patterns, subject)
        }
    }

    companion object {
        private val ALGORITHM_LISTS = setOf(
            "kexalgorithms", "hostkeyalgorithms", "pubkeyacceptedalgorithms", "pubkeyacceptedkeytypes", "ciphers", "macs",
        )

        /** What old devices need and current OpenSSH has switched off. */
        private val LEGACY_ALGORITHMS = listOf(
            "ssh-rsa", "ssh-dss", "diffie-hellman-group1-sha1", "diffie-hellman-group14-sha1",
            "diffie-hellman-group-exchange-sha1", "-cbc", "hmac-sha1", "hmac-md5",
        )

        /** Reads the text of a configuration file. Lines that make no sense are passed over. */
        fun parse(text: String): SshConfig {
            val lines = ArrayList<Line>()
            for (raw in text.lineSequence()) {
                val line = raw.trim { it == ' ' || it == '\t' || it == '\r' || it == '\u000c' }
                if (line.isEmpty() || line.startsWith("#")) continue
                // The keyword ends at white space or at an equals sign, which may have space around it.
                val end = line.indexOfFirst { it == ' ' || it == '\t' || it == '=' }.let { if (it < 0) line.length else it }
                val keyword = line.substring(0, end).lowercase()
                var rest = line.substring(end).trimStart(' ', '\t')
                if (rest.startsWith("=")) rest = rest.substring(1).trimStart(' ', '\t')
                lines.add(Line(keyword, words(rest), rest))
            }
            return SshConfig(lines)
        }

        /**
         * Splits the arguments of a line the way OpenSSH does: at white space, with single or
         * double quotes holding a word together, and `#` at the start of a word beginning a comment.
         */
        internal fun words(text: String): List<String> {
            val result = ArrayList<String>()
            var index = 0
            while (index < text.length) {
                val ch = text[index]
                if (ch == ' ' || ch == '\t') {
                    index++
                    continue
                }
                if (ch == '#') break
                val word = StringBuilder()
                var quote = 0.toChar()
                while (index < text.length) {
                    val c = text[index]
                    val next = text.getOrNull(index + 1)
                    if (c == '\\' && (next == '\'' || next == '"' || next == '\\' || (quote == 0.toChar() && next == ' '))) {
                        word.append(next)
                        index += 2
                        continue
                    }
                    if (quote == 0.toChar() && (c == ' ' || c == '\t')) break
                    if (quote == 0.toChar() && (c == '"' || c == '\'')) {
                        quote = c
                    } else if (quote != 0.toChar() && c == quote) {
                        quote = 0.toChar()
                    } else {
                        word.append(c)
                    }
                    index++
                }
                result.add(word.toString())
            }
            return result
        }

        /** Whether [text] matches [pattern], in which `*` stands for any text and `?` for one character. */
        internal fun glob(pattern: String, text: String): Boolean {
            var p = 0
            var t = 0
            var star = -1
            var resume = 0
            while (t < text.length) {
                if (p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t])) {
                    p++
                    t++
                } else if (p < pattern.length && pattern[p] == '*') {
                    star = p++
                    resume = t
                } else if (star >= 0) {
                    p = star + 1
                    t = ++resume
                } else {
                    return false
                }
            }
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }

        /** A comma-separated list of patterns as `Match` uses it: any may match, a negated one must not. */
        private fun patternListMatches(patterns: String, text: String): Boolean {
            var matched = false
            for (pattern in patterns.lowercase().split(',')) {
                if (pattern.startsWith("!")) {
                    if (glob(pattern.substring(1), text.lowercase())) return false
                } else if (glob(pattern, text.lowercase())) {
                    matched = true
                }
            }
            return matched
        }

        /** Whether the conditions of a `Match` line can be decided from the file alone. */
        private fun isCheckable(arguments: List<String>): Boolean {
            if (arguments.size == 1) return arguments[0].lowercase() == "all"
            if (arguments.isEmpty() || arguments.size % 2 != 0) return false
            return arguments.chunked(2).all { it[0].lowercase() == "host" || it[0].lowercase() == "originalhost" }
        }

        /** `%h` in a HostName stands for the name the host was asked for by. */
        private fun expand(hostName: String, name: String): String {
            val result = StringBuilder()
            var index = 0
            while (index < hostName.length) {
                val c = hostName[index]
                if (c == '%' && index + 1 < hostName.length && (hostName[index + 1] == 'h' || hostName[index + 1] == '%')) {
                    result.append(if (hostName[index + 1] == 'h') name else "%")
                    index += 2
                } else {
                    result.append(c)
                    index++
                }
            }
            return result.toString()
        }

        private fun flag(value: String): Boolean? = when (value.lowercase()) {
            "yes", "true" -> true
            "no", "false" -> false
            else -> null
        }

        /** `[user@]host[:port]` or the same as an `ssh://` address. Null for anything else. */
        internal fun parseJump(text: String): ConfigJump? {
            var rest = text.trim().removePrefix("ssh://").removeSuffix("/")
            if (rest.isEmpty()) return null
            var user: String? = null
            val at = rest.lastIndexOf('@')
            if (at >= 0) {
                user = rest.substring(0, at).takeIf { it.isNotEmpty() } ?: return null
                rest = rest.substring(at + 1)
            }
            val (host, port) = splitHostPort(rest) ?: return null
            if (host.isEmpty() || host.any { it.isWhitespace() }) return null
            return ConfigJump(host, user, port)
        }

        /**
         * The jump host behind the usual way of writing one as a command, `ssh -W %h:%p host`
         * (which is what `ProxyJump host` does), or null when [command] is something else.
         */
        internal fun jumpOf(command: String): ConfigJump? {
            val words = words(command)
            if (words.firstOrNull() != "ssh" || words.size < 4) return null
            val rest = words.drop(1).filter { it != "-q" && it != "-A" && it != "-a" && it != "-x" && it != "-T" }.toMutableList()
            val forward = rest.indexOf("-W")
            if (forward < 0 || rest.getOrNull(forward + 1) != "%h:%p") return null
            rest.removeAt(forward + 1)
            rest.removeAt(forward)
            var port: Int? = null
            var user: String? = null
            while (rest.size >= 3 && (rest[0] == "-p" || rest[0] == "-l")) {
                if (rest[0] == "-p") port = rest[1].toIntOrNull() ?: return null else user = rest[1]
                rest.removeAt(1)
                rest.removeAt(0)
            }
            val target = rest.singleOrNull()?.takeIf { !it.startsWith("-") } ?: return null
            val jump = parseJump(target) ?: return null
            return jump.copy(user = jump.user ?: user, port = jump.port ?: port)
        }

        /** Splits `host`, `host:port` and `[host]:port`; an address with several colons is a bare IPv6 address. */
        private fun splitHostPort(text: String): Pair<String, Int?>? {
            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close < 0) return null
                val host = text.substring(1, close)
                val after = text.substring(close + 1)
                if (after.isEmpty()) return host to null
                if (!after.startsWith(":")) return null
                val port = after.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                return host to port
            }
            val colon = text.lastIndexOf(':')
            if (colon < 0 || text.indexOf(':') != colon) return text to null
            val port = text.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return text.substring(0, colon) to port
        }

        /**
         * `[bind_address:]port` and, unless [dynamic], `host:hostport`. Null for what this app has
         * no use for, such as forwards to and from Unix sockets.
         */
        internal fun parseForward(listen: String, target: String?, dynamic: Boolean = false): ConfigForward? {
            if ('/' in listen) return null
            val bind: String?
            val bindPort: Int
            val whole = listen.toIntOrNull()
            if (whole != null) {
                bind = null
                bindPort = whole
            } else {
                val (host, port) = splitHostPort(listen) ?: return null
                bind = host
                bindPort = port ?: return null
            }
            if (bindPort !in 1..65535) return null
            if (dynamic) return ConfigForward(bind, bindPort)
            if (target == null || '/' in target) return null
            val (host, port) = splitHostPort(target) ?: return null
            if (host.isEmpty() || port == null) return null
            return ConfigForward(bind, bindPort, host, port)
        }
    }
}
