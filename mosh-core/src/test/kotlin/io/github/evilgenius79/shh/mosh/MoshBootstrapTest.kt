package io.github.evilgenius79.shh.mosh

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MoshBootstrapTest {
    private val key = "1RIhw7m9IgrxPMuH2JNNPQ"

    @Test
    fun `the connect line is found on its own`() {
        assertEquals(60001 to key, MoshBootstrap.parseConnect("MOSH CONNECT 60001 $key"))
        assertEquals(60001 to key, MoshBootstrap.parseConnect("MOSH CONNECT 60001 $key\n"))
    }

    @Test
    fun `the connect line is found among everything else a login prints`() {
        val output = "Welcome to Ubuntu 24.04\n * Documentation: https://help.ubuntu.com\n\n" +
            "MOSH CONNECT 60123 $key\n\n" +
            "mosh-server (mosh 1.4.0) [build mosh 1.4.0]\nCopyright 2012 Keith Winstein <mosh-devel@mit.edu>\n" +
            "[mosh-server detached, pid = 9808]\n"
        assertEquals(60123 to key, MoshBootstrap.parseConnect(output))
    }

    @Test
    fun `carriage returns around the connect line do not matter`() {
        // With a terminal on the SSH channel every newline arrives as CR LF, and the server
        // puts an extra blank line in front.
        assertEquals(60002 to key, MoshBootstrap.parseConnect("\r\n\r\nMOSH CONNECT 60002 $key\r\n\r\nmosh-server (mosh 1.4.0)\r\n"))
        assertEquals(60002 to key, MoshBootstrap.parseConnect("junk\rMOSH CONNECT 60002 $key\r"))
        assertEquals(60002 to key, MoshBootstrap.parseConnect("MOSH CONNECT 60002 $key  \t\r\n"))
    }

    @Test
    fun `the last connect line wins`() {
        // An earlier one can only come from the account's startup files.
        val other = "AAECAwQFBgcICQoLDA0ODw"
        assertEquals(60005 to key, MoshBootstrap.parseConnect("MOSH CONNECT 60001 $other\nMOSH CONNECT 60005 $key\n"))
    }

    @Test
    fun `output without a well formed connect line yields nothing`() {
        assertNull(MoshBootstrap.parseConnect(""))
        assertNull(MoshBootstrap.parseConnect("mosh-server: command not found\n"))
        // Not at the start of a line.
        assertNull(MoshBootstrap.parseConnect("echo MOSH CONNECT 60001 $key\n"))
        // Still arriving.
        assertNull(MoshBootstrap.parseConnect("MOSH CONNECT 60001 ${key.dropLast(3)}"))
        // Key too long, port out of range or not a number, trailing text.
        assertNull(MoshBootstrap.parseConnect("MOSH CONNECT 60001 ${key}A\n"))
        assertNull(MoshBootstrap.parseConnect("MOSH CONNECT 70000 $key\n"))
        assertNull(MoshBootstrap.parseConnect("MOSH CONNECT 0 $key\n"))
        assertNull(MoshBootstrap.parseConnect("MOSH CONNECT port $key\n"))
        assertNull(MoshBootstrap.parseConnect("MOSH CONNECT 60001 $key and more\n"))
        // Twenty-two base64 characters that are not a 128-bit key.
        assertNull(MoshBootstrap.parseConnect("MOSH CONNECT 60001 ${key.dropLast(1)}R\n"))
    }

    @Test
    fun `the server command asks for a UTF-8 locale and falls back to the one every Linux has`() {
        assertEquals(
            "mosh-server new -s -c 256 -l LANG=en_US.UTF-8 || mosh-server new -s -c 256 -l LANG=C.UTF-8",
            MoshBootstrap.serverCommand(),
        )
        assertEquals("mosh-server new -s -c 256 -l LANG=C.UTF-8", MoshBootstrap.serverCommand("C.UTF-8"))
        assertEquals(
            "mosh-server new -s -c 256 -l LANG=de_DE.UTF-8 -p 60000:60010 || mosh-server new -s -c 256 -l LANG=C.UTF-8 -p 60000:60010",
            MoshBootstrap.serverCommand("de_DE.UTF-8", "60000:60010"),
        )
        assertEquals("mosh-server new -s -c 256 -l LANG=C.UTF-8 -p 61000", MoshBootstrap.serverCommand("C.UTF-8", "61000"))
        assertEquals(
            "env MOSH_SERVER_NETWORK_TMOUT=86400 mosh-server new -s -c 256 -l LANG=en_US.UTF-8 || " +
                "env MOSH_SERVER_NETWORK_TMOUT=86400 mosh-server new -s -c 256 -l LANG=C.UTF-8",
            MoshBootstrap.serverCommand(abandonAfterSeconds = 86400),
        )
        assertFailsWith<IllegalArgumentException> { MoshBootstrap.serverCommand(abandonAfterSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { MoshBootstrap.serverCommand(abandonAfterSeconds = -5) }
    }

    @Test
    fun `nothing that a shell would interpret gets into the server command`() {
        assertFailsWith<IllegalArgumentException> { MoshBootstrap.serverCommand("en_US.UTF-8; rm -rf ~") }
        assertFailsWith<IllegalArgumentException> { MoshBootstrap.serverCommand("\$(id)") }
        assertFailsWith<IllegalArgumentException> { MoshBootstrap.serverCommand("") }
        assertFailsWith<IllegalArgumentException> { MoshBootstrap.serverCommand(udpPorts = "60000-60010") }
        assertFailsWith<IllegalArgumentException> { MoshBootstrap.serverCommand(udpPorts = "60000:60010:1") }
        assertFailsWith<IllegalArgumentException> { MoshBootstrap.serverCommand(udpPorts = "1 2") }
    }
}
