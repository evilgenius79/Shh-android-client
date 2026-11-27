package io.github.evilgenius79.shh

import io.github.evilgenius79.shh.data.ConfigImport
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.InternetProtocol
import io.github.evilgenius79.shh.ssh.SshConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Making hosts for the app out of an OpenSSH configuration file. */
class ConfigImportTest {
    private var ids = 0

    private fun plan(text: String, saved: List<Host> = emptyList()): ConfigImport.Plan =
        ConfigImport.plan(SshConfig.parse(text.trimIndent()), saved) { "id-${++ids}" }

    private fun ConfigImport.Plan.host(name: String): Host = assertNotNull(hosts.firstOrNull { it.name == name }, "no host called $name among ${hosts.map { it.name }}")

    /** The hosts [host] is reached through, the one connected to first coming first. */
    private fun route(host: Host, all: List<Host>): List<String> {
        val route = ArrayList<String>()
        var next = host.jumpHostId
        while (next != null) {
            val through = assertNotNull(all.firstOrNull { it.id == next }, "${host.title} goes through a host that does not exist")
            route.add(0, through.title)
            assertTrue(route.size < 20, "the hosts go round in a circle: $route")
            next = through.jumpHostId
        }
        return route
    }

    @Test
    fun aHostOfTheFileBecomesAHostOfTheApp() {
        val plan = plan(
            """
            Host web
                HostName web01.example.org
                User deploy
                Port 2222
                LocalForward 8080 localhost:80
                LocalForward *:5432 db.internal:5432
                RemoteForward 7000 localhost:7001
                DynamicForward localhost:1080
                ForwardAgent yes
                AddressFamily inet6
                ServerAliveInterval 15
                Compression yes
                RemoteCommand tmux new -A -s main
            Host router
                HostName 192.168.1.1
                User root
                HostKeyAlgorithms +ssh-rsa
                ServerAliveInterval 0
            Host plain
            Host slow
                User me
                ServerAliveInterval 86400
            """,
        )
        assertEquals(listOf("web", "router", "plain", "slow"), plan.hosts.map { it.name })
        val web = plan.host("web")
        assertEquals("deploy@web01.example.org:2222", web.address)
        assertEquals("tmux new -A -s main", web.startupCommand)
        assertTrue(web.forwardAgent && web.compression && !web.allowLegacyAlgorithms)
        assertEquals(InternetProtocol.IPV6, web.internetProtocol)
        assertEquals(15, web.keepAliveSeconds)
        assertEquals(
            listOf("LOCAL 127.0.0.1:8080 localhost:80", "LOCAL 0.0.0.0:5432 db.internal:5432", "REMOTE 127.0.0.1:7000 localhost:7001", "DYNAMIC 127.0.0.1:1080 :0"),
            web.forwards.map { "${it.kind} ${it.bindAddress}:${it.bindPort} ${it.destinationHost}:${it.destinationPort}" },
        )
        assertTrue(web.forwards.all { it.enabled })
        assertEquals(ForwardKind.DYNAMIC, web.forwards.last().kind)

        val router = plan.host("router")
        assertTrue(router.allowLegacyAlgorithms, "an old device that needs ssh-rsa gets the legacy algorithms")
        assertEquals(0, router.keepAliveSeconds, "keep-alives switched off stay off")

        val plain = plan.host("plain")
        assertEquals("plain", plain.host, "without a HostName the name is the address")
        assertEquals(22, plain.port)
        assertEquals(Host(id = "", host = "").keepAliveSeconds, plain.keepAliveSeconds, "nothing said about keep-alives leaves the app's default")
        assertEquals(InternetProtocol.AUTOMATIC, plain.internetProtocol)
        assertEquals(3600, plan.host("slow").keepAliveSeconds, "no more than the app allows")

        assertEquals(plan.hosts.size + web.forwards.size, (plan.hosts.map { it.id } + web.forwards.map { it.id }).distinct().size, "every id is its own")
        assertEquals(listOf("plain"), plan.withoutUser)
        assertEquals("phone-user", plan.hostsFor(" phone-user ").first { it.name == "plain" }.username)
        assertEquals("deploy", plan.hostsFor("phone-user").first { it.name == "web" }.username, "a user the file names is kept")
        assertEquals(emptyList(), plan.remarks)
    }

    @Test
    fun jumpHostsBecomeTheHostsToConnectThrough() {
        val plan = plan(
            """
            Host inner
                HostName 10.0.0.5
                User app
                ProxyJump bastion
            Host bastion
                HostName bastion.example.org
                User ops
            Host far
                User me
                ProxyJump jumper@gate.example.org:2200
            Host deep
                User me
                ProxyJump bastion,second.example.org,third@10.9.9.9
            Host direct
                User me
                ProxyJump none
            Host *
                User fallback
            """,
        )
        val all = plan.hosts
        assertEquals(listOf("bastion"), route(plan.host("inner"), all))
        assertTrue(all.indexOf(plan.host("bastion")) < all.indexOf(plan.host("inner")), "a host comes after the one it goes through")
        assertEquals(1, all.count { it.name == "bastion" }, "the jump host is the host of that name, not a second one")

        // A jump host the file does not name gets an entry of its own.
        assertEquals(listOf("jumper@gate.example.org:2200"), route(plan.host("far"), all))
        val gate = plan.host("jumper@gate.example.org:2200")
        assertEquals("jumper@gate.example.org:2200", gate.address)

        // Several jump hosts: ssh goes to the first, from there to the second, and so on.
        assertEquals(listOf("bastion", "second.example.org via bastion", "third@10.9.9.9 via second.example.org via bastion"), route(plan.host("deep"), all))
        val second = plan.host("second.example.org via bastion")
        assertEquals("fallback@second.example.org", second.address, "what the file says about every host holds for jump hosts too")
        assertEquals("third@10.9.9.9", plan.host("third@10.9.9.9 via second.example.org via bastion").address)

        assertNull(plan.host("direct").jumpHostId)
        assertEquals(emptyList(), plan.withoutUser)
    }

    @Test
    fun aHostThatIsOnlyJumpedThroughHasNoForwardsOrCommand() {
        val plan = plan(
            """
            Host target
                User me
                ProxyJump ops@gate
            Host gate
                User someone-else
                LocalForward 8080 localhost:80
                RemoteCommand top
            """,
        )
        assertEquals(1, plan.host("gate").forwards.size, "the host itself keeps what the file gives it")
        val stop = plan.host("ops@gate")
        assertEquals("ops@gate", stop.address)
        assertEquals(emptyList(), stop.forwards)
        assertEquals("", stop.startupCommand)
        assertEquals(listOf("ops@gate"), route(plan.host("target"), plan.hosts))
    }

    @Test
    fun aFileThatJumpsInCirclesStillGivesHostsThatCanBeUsed() {
        // Every host goes through the bastion, and nothing exempts the bastion itself.
        val plan = plan(
            """
            Host bastion
                HostName bastion.example.org
            Host a
            Host b
                ProxyJump a
            Host *
                User me
                ProxyJump bastion
            Host x
                ProxyJump y
            Host y
                ProxyJump x
            """,
        )
        for (host in plan.hosts) route(host, plan.hosts)
        assertNull(plan.host("bastion").jumpHostId)
        assertEquals(listOf("bastion"), route(plan.host("a"), plan.hosts))
        assertEquals(listOf("bastion", "a"), route(plan.host("b"), plan.hosts))
    }

    @Test
    fun namesThatAreSavedAlreadyAreLeftAloneAndImportingTwiceAddsNothing() {
        val text = """
            Host web
                User deploy
                ProxyJump bastion
            Host bastion
                User ops
            Host far
                User me
                ProxyJump jumper@gate:2200,second
            """
        val saved = listOf(Host(id = "kept", name = "bastion", host = "the.saved.one", username = "saved-user", port = 2022))
        val first = plan(text, saved)
        assertEquals(listOf("bastion"), first.alreadySaved)
        assertTrue(first.hosts.none { it.name == "bastion" })
        assertEquals("kept", first.host("web").jumpHostId, "a new host goes through the saved host of that name")
        assertEquals(listOf("web", "jumper@gate:2200", "second via jumper@gate:2200", "far"), first.hosts.map { it.name })

        val after = saved + first.hostsFor("")
        val second = plan(text, after)
        assertEquals(emptyList(), second.hosts)
        assertEquals(listOf("web", "bastion", "far"), second.alreadySaved)
    }

    @Test
    fun whatIsLeftOutIsPointedOut() {
        val plan = plan(
            """
            Include config.d/*
            Host a
                User me
                IdentityFile ~/.ssh/id_a
            Host b
                User me
                IdentityFile ~/.ssh/id_b
                ProxyCommand nc -X connect -x proxy:3128 %h %p
            Host c
                User me
                ProxyCommand ssh -W %h:%p a
            Match exec "true"
                User other
            """,
        )
        assertEquals(4, plan.remarks.size, plan.remarks.toString())
        assertTrue(plan.remarks.any { "includes other files" in it })
        assertTrue(plan.remarks.any { "Match" in it })
        assertTrue(plan.remarks.any { it.startsWith("a, b log in with key files.") }, plan.remarks.toString())
        assertTrue(plan.remarks.any { it.startsWith("b connects through a ProxyCommand") && it.endsWith("It is added without it.") }, plan.remarks.toString())
        assertNull(plan.host("b").jumpHostId)
        assertEquals(listOf("a"), route(plan.host("c"), plan.hosts), "a command that is just a jump host is understood")
    }

    @Test
    fun aFileWithoutHostsGivesAnEmptyPlan() {
        val plan = plan("Host *\n    User me\n    ServerAliveInterval 30\n")
        assertEquals(emptyList(), plan.hosts)
        assertEquals(emptyList(), plan.alreadySaved)
        assertEquals(emptyList(), plan.withoutUser)
    }
}
