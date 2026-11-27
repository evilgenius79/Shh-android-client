package io.github.evilgenius79.shh.data

import io.github.evilgenius79.shh.ssh.AddressFamily
import io.github.evilgenius79.shh.ssh.ConfigForward
import io.github.evilgenius79.shh.ssh.ConfigHost
import io.github.evilgenius79.shh.ssh.ConfigJump
import io.github.evilgenius79.shh.ssh.SshConfig

/** Turns an OpenSSH configuration file (`~/.ssh/config`) into hosts for the app. */
object ConfigImport {
    /** What importing a file would do. Nothing is saved until [hostsFor] is stored. */
    class Plan(
        /**
         * The hosts to add. Each one that others connect through comes before them. A host for
         * which the file gives no user name has none yet, see [withoutUser].
         */
        val hosts: List<Host>,
        /** Names in the file that saved hosts already have. Those hosts are left as they are. */
        val alreadySaved: List<String>,
        /** Titles of the new hosts for which the file names no user. */
        val withoutUser: List<String>,
        /** What was left out and why, for the user to read. */
        val remarks: List<String>,
    ) {
        /** [hosts] with [user] filled in where the file named none. */
        fun hostsFor(user: String): List<Host> = hosts.map { if (it.username.isBlank()) it.copy(username = user.trim()) else it }
    }

    /**
     * Plans the import of [config] next to the hosts [saved] already. A name of the file that
     * a saved host already has is skipped, so importing a file twice adds nothing the second time.
     */
    fun plan(config: SshConfig, saved: List<Host>, newId: () -> String = AppStore::newId): Plan {
        val builder = Builder(config, saved, newId)
        val alreadySaved = ArrayList<String>()
        for (alias in config.aliases) {
            if (saved.any { it.name == alias }) alreadySaved.add(alias) else builder.hostNamed(alias)
        }
        val added = builder.added
        val remarks = ArrayList<String>()
        if (config.hasIncludes) remarks.add("The file includes other files. They were not read: import them one by one.")
        if (config.hasUncheckedMatches) remarks.add("Some Match blocks depend on things this app cannot check. Their settings were left out.")
        if (builder.withKeyFiles.isNotEmpty()) {
            val several = builder.withKeyFiles.size > 1
            remarks.add(
                "${names(builder.withKeyFiles)} ${if (several) "log in with key files" else "logs in with a key file"}. " +
                    "Keys are not part of this file: import them under Keys and choose them in the host's settings.",
            )
        }
        if (builder.withProxyCommand.isNotEmpty()) {
            val several = builder.withProxyCommand.size > 1
            remarks.add(
                "${names(builder.withProxyCommand)} ${if (several) "connect" else "connects"} through a ProxyCommand, which this app cannot run. " +
                    "${if (several) "They are" else "It is"} added without it.",
            )
        }
        return Plan(added, alreadySaved, added.filter { it.username.isBlank() }.map { it.title }, remarks)
    }

    private fun names(hosts: List<String>): String = if (hosts.size <= 4) hosts.joinToString(", ") else "${hosts.take(3).joinToString(", ")} and ${hosts.size - 3} more"

    private class Builder(private val config: SshConfig, private val saved: List<Host>, private val newId: () -> String) {
        val added = ArrayList<Host>()
        val withKeyFiles = ArrayList<String>()
        val withProxyCommand = ArrayList<String>()

        /** Names whose hosts are being put together right now, to stop a file that jumps in circles. */
        private val underway = HashSet<String>()

        private fun known(): List<Host> = saved + added

        /** The host the file calls [alias]: a saved one, one planned already, or a new one. */
        fun hostNamed(alias: String): Host? {
            known().firstOrNull { it.name == alias }?.let { return it }
            if (!underway.add(alias)) return null
            try {
                val resolved = config.resolve(alias)
                val host = host(resolved, name = alias, jumpHostId = chain(resolved.jumps))
                if (resolved.identityFiles.isNotEmpty()) withKeyFiles.add(alias)
                if (resolved.proxyCommand != null) withProxyCommand.add(alias)
                added.add(host)
                return host
            } finally {
                underway.remove(alias)
            }
        }

        /**
         * The id of the host to connect through for `ProxyJump` [jumps], or null for a direct
         * connection. ssh goes to the first of them, from there to the second and so on, so the
         * host to connect through is the last, which itself goes through the one before it.
         */
        private fun chain(jumps: List<ConfigJump>): String? {
            var through: Host? = null
            for ((index, jump) in jumps.withIndex()) {
                through = if (index == 0) first(jump) else later(jump, through ?: return null)
                if (through == null) return null
            }
            return through?.id
        }

        /** The first hop: ssh reaches it the way the file says, its own jump hosts included. */
        private fun first(jump: ConfigJump): Host? {
            if (jump.user == null && jump.port == null) return hostNamed(jump.host)
            if (jump.host in underway) return null
            val resolved = config.resolve(jump.host)
            val wanted = stop(resolved, jump, jumpHostId = null)
            underway.add(jump.host)
            try {
                return same(wanted.copy(jumpHostId = chain(resolved.jumps)), label(jump))
            } finally {
                underway.remove(jump.host)
            }
        }

        /** A later hop is reached through the hop before it, whatever the file says about its own jump hosts. */
        private fun later(jump: ConfigJump, before: Host): Host {
            val resolved = config.resolve(jump.host)
            return same(stop(resolved, jump, jumpHostId = before.id), "${label(jump)} via ${before.title}")
        }

        /** A host that exists to be connected through: no forwards and no command of its own, as with ssh. */
        private fun stop(resolved: ConfigHost, jump: ConfigJump, jumpHostId: String?): Host = host(resolved, name = "", jumpHostId = jumpHostId).copy(
            username = jump.user ?: resolved.user.orEmpty(),
            port = jump.port ?: resolved.port ?: 22,
            forwards = emptyList(),
            startupCommand = "",
        )

        private fun label(jump: ConfigJump): String = (jump.user?.let { "$it@" } ?: "") + jump.host + (jump.port?.let { ":$it" } ?: "")

        /** A host that connects exactly like [wanted] if there is one, or else [wanted] under the name [label]. */
        private fun same(wanted: Host, label: String): Host {
            known().firstOrNull {
                it.host == wanted.host && it.port == wanted.port && it.username == wanted.username && it.jumpHostId == wanted.jumpHostId
            }?.let { return it }
            var name = label
            var number = 2
            while (known().any { it.name == name }) name = "$label (${number++})"
            return wanted.copy(id = newId(), name = name).also(added::add)
        }

        private fun host(resolved: ConfigHost, name: String, jumpHostId: String?): Host = Host(
            id = newId(),
            name = name,
            host = resolved.hostName,
            port = resolved.port ?: 22,
            username = resolved.user.orEmpty(),
            jumpHostId = jumpHostId,
            // RemoteCommand runs in place of the shell; here it is typed into the shell instead.
            startupCommand = resolved.remoteCommand.orEmpty(),
            forwards = resolved.localForwards.map { rule(ForwardKind.LOCAL, it) } +
                resolved.remoteForwards.map { rule(ForwardKind.REMOTE, it) } +
                resolved.dynamicForwards.map { rule(ForwardKind.DYNAMIC, it) },
            allowLegacyAlgorithms = resolved.wantsLegacyAlgorithms,
            compression = resolved.compression == true,
            // Without a ServerAliveInterval the app's own default stays: a phone needs keep-alives.
            keepAliveSeconds = resolved.serverAliveInterval?.coerceIn(0, 3600) ?: Host(id = "", host = "").keepAliveSeconds,
            internetProtocol = when (resolved.addressFamily) {
                AddressFamily.IPV4 -> InternetProtocol.IPV4
                AddressFamily.IPV6 -> InternetProtocol.IPV6
                else -> InternetProtocol.AUTOMATIC
            },
            forwardAgent = resolved.forwardAgent == true,
        )

        private fun rule(kind: ForwardKind, forward: ConfigForward): ForwardRule = ForwardRule(
            id = newId(),
            kind = kind,
            bindAddress = when (forward.bindAddress) {
                // Like ssh, listen for this device only unless the file asks for more.
                null, "localhost" -> "127.0.0.1"
                "", "*" -> "0.0.0.0"
                else -> forward.bindAddress!!
            },
            bindPort = forward.bindPort,
            destinationHost = forward.host.orEmpty(),
            destinationPort = forward.hostPort ?: 0,
        )
    }
}
