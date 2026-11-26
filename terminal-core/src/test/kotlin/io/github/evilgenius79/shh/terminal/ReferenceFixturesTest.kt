package io.github.evilgenius79.shh.terminal

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Differential tests: every fixture is the raw output of a real program (vim, less, htop, ...)
 * recorded by `tools/record_fixtures.py` together with the screen that tmux, acting as the
 * reference terminal, showed afterwards. Replaying the bytes into our emulator must give the same
 * screen text and the same cursor position.
 */
@RunWith(Parameterized::class)
class ReferenceFixturesTest(private val name: String) {
    @Test
    fun matchesReferenceTerminal() {
        val expectedLines = resource("$name.expected").readText(Charsets.UTF_8).split('\n')
        val header = expectedLines[0].split(' ')
        val cols = header[1].toInt()
        val rows = header[2].toInt()
        val expectedCursorX = header[3].toInt()
        val expectedCursorY = header[4].toInt()
        val expectedAlternate = header[5] == "1"
        val expectedScreen = expectedLines.subList(1, 1 + rows)

        val terminal = TestTerminal(cols, rows)
        val raw = resource("$name.raw").readBytes()
        // Feed in uneven chunks so that sequences get split in arbitrary places.
        var offset = 0
        var chunk = 1
        while (offset < raw.size) {
            val n = minOf(chunk, raw.size - offset)
            terminal.emulator.append(raw, offset, n)
            offset += n
            chunk = chunk % 97 + 3
        }

        val actualScreen = terminal.lines()
        val differences = (0 until rows).filter { expectedScreen[it] != actualScreen[it] }
        if (differences.isNotEmpty()) {
            val report = StringBuilder("$name: ${differences.size} of $rows rows differ\n")
            for (row in differences.take(12)) {
                report.append("row %2d expected |%s|\n".format(row, expectedScreen[row]))
                report.append("       actual   |%s|\n".format(actualScreen[row]))
            }
            fail(report.toString())
        }
        assertEquals(expectedAlternate, terminal.emulator.isAlternateScreenActive, "$name: alternate screen")
        // tmux reports a pending wrap as a cursor one past the last column.
        assertEquals(minOf(expectedCursorX, cols - 1) to expectedCursorY, terminal.emulator.cursorCol to terminal.emulator.cursorRow, "$name: cursor (x to y)")
    }

    private fun resource(file: String): File = File(fixtureDir(), file)

    companion object {
        private fun fixtureDir(): File {
            val url = ReferenceFixturesTest::class.java.classLoader.getResource("fixtures")
                ?: error("fixtures directory is missing from the test resources")
            return File(url.toURI())
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<String>> {
            val names = fixtureDir().listFiles { f -> f.name.endsWith(".raw") }!!.map { it.name.removeSuffix(".raw") }.sorted()
            assertTrue(names.size >= 20, "expected the recorded fixtures to be present")
            return names.map { arrayOf(it) }
        }
    }
}
