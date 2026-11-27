package io.github.evilgenius79.shh.ssh

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading `~/.ssh/config`. Where the `ssh` program is installed, what is read is held against
 * what `ssh -G` makes of the same file, which is the authority on how such a file is meant.
 */
class SshConfigTest {
    /** A file that uses the notations people use: quotes, `=`, comments, patterns, negation, tokens. */
    private val sample = """
        # Settings before the first Host line apply to every host.
        ServerAliveInterval 45   # a comment after a setting
        Compression=yes

        Host web  db
            HostName %h.example.org
            User "deploy user"
            Port = 2222
            LocalForward 8080 localhost:80
            LocalForward 127.0.0.1:5432 db.internal:5432
            LocalForward [::1]:9000 [2001:db8::1]:9001
            RemoteForward 7000 localhost:7001
            RemoteForward *:7002 localhost:7003
            DynamicForward 1080
            DynamicForward localhost:1081
            ForwardAgent yes
            IdentityFile ~/.ssh/id_web

        Host !secret.corp *.corp
            ProxyJump bastion
            User corp

        Host bastion
            hostname 203.0.113.9
            AddressFamily inet
            ProxyJump none
            ForwardAgent no

        Host multi
            ProxyJump a@first:2200,second
            Port 22
            Port 23
            AddressFamily inet6
            Compression no

        Host old-router
        	HostName 192.168.1.1
        	User root
        	KexAlgorithms +diffie-hellman-group1-sha1
        	HostKeyAlgorithms +ssh-rsa

        Host six
            HostName 2001:db8::7
            ProxyJump [2001:db8::1]:2222

        Match host *.example.org
            ServerAliveInterval 5
            DynamicForward 1082

        Match originalhost multi,other
            User matched

        Match all
            ForwardAgent yes

        Host *
            User fallback
            ServerAliveInterval 10
            ProxyJump default-jump
    """.trimIndent()

    private val names = listOf("web", "db", "x.corp", "secret.corp", "bastion", "multi", "old-router", "six", "other", "somewhere.example.org")

    @Test
    fun theFileNamesItsHosts() {
        val config = SshConfig.parse(sample)
        assertEquals(listOf("web", "db", "bastion", "multi", "old-router", "six"), config.aliases, "patterns and negations are not hosts")
        assertTrue(!config.hasIncludes && !config.hasUncheckedMatches)
    }

    @Test
    fun settingsAreFoundTheWaySshFindsThem() {
        val config = SshConfig.parse(sample)
        val web = config.resolve("web")
        assertEquals("web.example.org", web.hostName)
        assertEquals("deploy user", web.user)
        assertEquals(2222, web.port)
        assertEquals(45, web.serverAliveInterval, "the first value wins, and that is the one at the top")
        assertEquals(true, web.compression)
        assertEquals(true, web.forwardAgent)
        assertEquals(
            listOf(ConfigForward(null, 8080, "localhost", 80), ConfigForward("127.0.0.1", 5432, "db.internal", 5432), ConfigForward("::1", 9000, "2001:db8::1", 9001)),
            web.localForwards,
        )
        assertEquals(listOf(ConfigForward(null, 7000, "localhost", 7001), ConfigForward("*", 7002, "localhost", 7003)), web.remoteForwards)
        // The Match block sees the HostName that was found before it.
        assertEquals(listOf(ConfigForward(null, 1080), ConfigForward("localhost", 1081), ConfigForward(null, 1082)), web.dynamicForwards)
        assertEquals(listOf(ConfigJump("default-jump")), web.jumps)
        assertEquals(listOf("~/.ssh/id_web"), web.identityFiles)

        assertEquals("db.example.org", config.resolve("db").hostName, "%h is the name the host was asked for by")
        assertEquals(listOf(ConfigJump("bastion")), config.resolve("x.corp").jumps)
        assertEquals("corp", config.resolve("x.corp").user)
        assertEquals("fallback", config.resolve("secret.corp").user, "a negated pattern rules the line out")

        val bastion = config.resolve("bastion")
        assertEquals(emptyList(), bastion.jumps, "\"none\" is a decision too: the later default does not apply")
        assertEquals(AddressFamily.IPV4, bastion.addressFamily)
        assertEquals(false, bastion.forwardAgent)

        val multi = config.resolve("multi")
        assertEquals(listOf(ConfigJump("first", "a", 2200), ConfigJump("second")), multi.jumps)
        assertEquals(22, multi.port)
        assertEquals("matched", multi.user)
        assertEquals(true, multi.compression, "\"Compression=yes\" at the top came first")
        assertEquals(AddressFamily.IPV6, multi.addressFamily)

        assertTrue(config.resolve("old-router").wantsLegacyAlgorithms)
        assertTrue(!web.wantsLegacyAlgorithms)
        assertEquals("2001:db8::7", config.resolve("six").hostName)
        assertEquals(listOf(ConfigJump("2001:db8::1", null, 2222)), config.resolve("six").jumps)

        // A name the file does not mention still gets what applies to every host.
        val other = config.resolve("other")
        assertEquals("other", other.hostName)
        assertEquals("matched", other.user)
        assertNull(other.port)
    }

    @Test
    fun whatIsReadAgreesWithTheSshProgram() {
        assumeTrue("the ssh program is not installed", haveSsh())
        val directory = Files.createTempDirectory("shh-config").toFile()
        try {
            val file = File(directory, "config").apply { writeText(sample + "\n") }
            val config = SshConfig.parse(sample)
            for (name in names) {
                val theirs = sshResolves(file, name)
                assertEquals(theirs, ours(config.resolve(name), fallbackUser = "fallback"), "for \"ssh $name\"")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aJumpWrittenAsACommandIsStillAJump() {
        val config = SshConfig.parse(
            """
            Host a
                ProxyCommand ssh -W %h:%p bastion
            Host b
                ProxyCommand ssh -q -W %h:%p -p 2200 -l jumper gate.example.org
            Host c
                ProxyCommand ssh ops@gate:2022 -W %h:%p
            Host d
                ProxyCommand nc -X 5 -x proxy:1080 %h %p
                ProxyJump ignored
            Host e
                ProxyJump first
                ProxyCommand ssh -W %h:%p ignored
            Host f
                ProxyCommand none
            Host *
                ProxyJump default
            """.trimIndent(),
        )
        assertEquals(listOf(ConfigJump("bastion")), config.resolve("a").jumps)
        assertEquals(listOf(ConfigJump("gate.example.org", "jumper", 2200)), config.resolve("b").jumps)
        assertEquals(listOf(ConfigJump("gate", "ops", 2022)), config.resolve("c").jumps)
        val d = config.resolve("d")
        assertEquals("nc -X 5 -x proxy:1080 %h %p", d.proxyCommand)
        assertEquals(emptyList(), d.jumps, "whichever of the two comes first decides")
        assertEquals(listOf(ConfigJump("first")), config.resolve("e").jumps)
        assertNull(config.resolve("e").proxyCommand)
        assertEquals(emptyList(), config.resolve("f").jumps)
        assertNull(config.resolve("f").proxyCommand)
        assertEquals(listOf(ConfigJump("default")), config.resolve("g").jumps)
    }

    @Test
    fun whatCannotBeDecidedFromTheFileIsLeftOutAndSaidSo() {
        val config = SshConfig.parse(
            """
            Include ~/.ssh/config.d/*
            Host box
                HostName box.example.org
                RemoteCommand tmux new -A -s main   # this is part of the command
                LocalForward /run/local.sock remote:80
                LocalForward 8080 /var/run/docker.sock
                LocalForward 99999 host:80
                LocalForward 8081 host
                Port seventy
                Bogus-Option whatever
            Match exec "test -f /tmp/flag"
                User from-exec
            Match user root host box
                Port 2200
            Host box
                User later
            """.trimIndent(),
        )
        assertTrue(config.hasIncludes)
        assertTrue(config.hasUncheckedMatches)
        val box = config.resolve("box")
        assertEquals("later", box.user, "a Match that runs a command is not obeyed")
        assertNull(box.port)
        assertEquals(emptyList(), box.localForwards, "forwards to Unix sockets and malformed ones are passed over")
        assertEquals("tmux new -A -s main   # this is part of the command", box.remoteCommand, "a command is the rest of its line")
    }

    @Test
    fun anEmptyOrMeaninglessFileHasNoHosts() {
        assertEquals(emptyList(), SshConfig.parse("").aliases)
        assertEquals(emptyList(), SshConfig.parse("\n\n# nothing\n   \n").aliases)
        assertEquals(emptyList(), SshConfig.parse("Host *\n  User me\nHost *.example.org !x\n").aliases)
        assertEquals(emptyList(), SshConfig.parse("\u0000\u0001 binary ÿ\nPK\u0003\u0004").aliases)
        assertEquals(listOf("a b"), SshConfig.parse("Host \"a b\"").aliases)
        assertEquals(listOf("one", "two"), SshConfig.parse("HOST=one two\r\nhost one\r\n").aliases, "Windows line ends, and no host twice")
    }

    @Test
    fun patternsMatchLikeTheShellsDo() {
        assertTrue(SshConfig.glob("*", ""))
        assertTrue(SshConfig.glob("*.example.org", "a.b.example.org"))
        assertTrue(!SshConfig.glob("*.example.org", "example.org"))
        assertTrue(SshConfig.glob("web-??", "web-01"))
        assertTrue(!SshConfig.glob("web-??", "web-1"))
        assertTrue(SshConfig.glob("a*b*c", "a-b-b-c"))
        assertTrue(!SshConfig.glob("a*b*c", "a-b-b-d"))
        assertTrue(!SshConfig.glob("Web", "web"), "names are told apart by case")
    }

    // ----- the same in the words of "ssh -G" -----

    private val compared = setOf(
        "hostname", "user", "port", "proxyjump", "localforward", "remoteforward", "dynamicforward",
        "forwardagent", "addressfamily", "serveraliveinterval", "compression",
    )

    private fun haveSsh(): Boolean = try {
        ProcessBuilder("ssh", "-V").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    } catch (e: java.io.IOException) {
        false
    }

    private fun sshResolves(file: File, name: String): List<String> {
        val process = ProcessBuilder("ssh", "-G", "-F", file.path, name).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0, "ssh -G failed: $output")
        return output.lines().filter { it.substringBefore(' ') in compared }.sorted()
    }

    /** [host] as `ssh -G` would print it. */
    private fun ours(host: ConfigHost, fallbackUser: String): List<String> {
        fun end(address: String?, port: Int) = if (address == null) "$port" else "[$address]:$port"
        val lines = ArrayList<String>()
        lines += "hostname ${host.hostName}"
        lines += "user ${host.user ?: fallbackUser}"
        lines += "port ${host.port ?: 22}"
        if (host.jumps.isNotEmpty()) {
            lines += "proxyjump " + host.jumps.joinToString(",") { jump ->
                val address = if (':' in jump.host) "[${jump.host}]" else jump.host
                (jump.user?.let { "$it@" } ?: "") + address + (jump.port?.let { ":$it" } ?: "")
            }
        }
        for (forward in host.localForwards) lines += "localforward ${end(forward.bindAddress, forward.bindPort)} ${end(forward.host, forward.hostPort!!)}"
        for (forward in host.remoteForwards) lines += "remoteforward ${end(forward.bindAddress, forward.bindPort)} ${end(forward.host, forward.hostPort!!)}"
        for (forward in host.dynamicForwards) lines += "dynamicforward ${end(forward.bindAddress, forward.bindPort)}"
        lines += "forwardagent " + if (host.forwardAgent == true) "yes" else "no"
        lines += "addressfamily " + when (host.addressFamily) {
            AddressFamily.IPV4 -> "inet"
            AddressFamily.IPV6 -> "inet6"
            else -> "any"
        }
        lines += "serveraliveinterval ${host.serverAliveInterval ?: 0}"
        lines += "compression " + if (host.compression == true) "yes" else "no"
        return lines.sorted()
    }
}
