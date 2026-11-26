package io.github.evilgenius79.shh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WcWidthAndColorsTest {
    @Test
    fun widths() {
        assertEquals(1, WcWidth.width('a'.code))
        assertEquals(1, WcWidth.width(0xE9)) // é
        assertEquals(1, WcWidth.width(0xAD)) // soft hyphen
        assertEquals(1, WcWidth.width(0x2500)) // box drawing
        assertEquals(1, WcWidth.width(0x2764)) // heavy black heart: text presentation
        assertEquals(0, WcWidth.width(0x0301)) // combining acute
        assertEquals(0, WcWidth.width(0x200D)) // zero width joiner
        assertEquals(0, WcWidth.width(0xFE0F)) // variation selector 16
        assertEquals(0, WcWidth.width(0x1160)) // hangul jungseong filler
        assertEquals(2, WcWidth.width(0x1100)) // hangul choseong
        assertEquals(2, WcWidth.width(0x4E2D)) // CJK
        assertEquals(2, WcWidth.width(0xAC00)) // hangul syllable
        assertEquals(2, WcWidth.width(0xFF21)) // fullwidth A
        assertEquals(2, WcWidth.width(0x1F600)) // emoji
        assertEquals(2, WcWidth.width(0x20000)) // CJK extension B
        assertEquals(1, WcWidth.width(0x10FFFF))
        assertEquals(6, WcWidth.width("a\u4e2de\u0301\ud83d\ude00"))
    }

    @Test
    fun widthTablesAreSortedAndDisjoint() {
        for (table in listOf(WcWidthTables.ZERO_WIDTH, WcWidthTables.WIDE)) {
            assertEquals(0, table.size % 2)
            for (i in table.indices step 2) {
                assertTrue(table[i] <= table[i + 1])
                if (i > 0) assertTrue(table[i - 1] < table[i])
            }
        }
    }

    @Test
    fun paletteLayout() {
        val colors = TerminalColors(ColorScheme.BLACK)
        assertEquals(0x000000, colors.paletteColor(16))
        assertEquals(0x00005F, colors.paletteColor(17))
        assertEquals(0xFF0000, colors.paletteColor(196))
        assertEquals(0xFFFFFF, colors.paletteColor(231))
        assertEquals(0x080808, colors.paletteColor(232))
        assertEquals(0xEEEEEE, colors.paletteColor(255))
        assertEquals(ColorScheme.BLACK.ansi(1), colors.paletteColor(1))
    }

    @Test
    fun resolvingColors() {
        val colors = TerminalColors(ColorScheme.DRACULA)
        assertEquals(ColorScheme.DRACULA.foreground, colors.resolve(TextStyle.COLOR_DEFAULT, true))
        assertEquals(ColorScheme.DRACULA.background, colors.resolve(TextStyle.COLOR_DEFAULT, false))
        assertEquals(ColorScheme.DRACULA.ansi(2), colors.resolve(TextStyle.indexed(2), true))
        assertEquals(0x0A141E, colors.resolve(TextStyle.rgb(10, 20, 30), false))
    }

    @Test
    fun parsingColorSpecs() {
        assertEquals(0xFF0080, TerminalColors.parse("rgb:ff/00/80"))
        assertEquals(0xFF0080, TerminalColors.parse("rgb:ffff/0000/8080"))
        assertEquals(0xFF00FF, TerminalColors.parse("rgb:f/0/f"))
        assertEquals(0x123456, TerminalColors.parse("#123456"))
        assertEquals(0xFF0000, TerminalColors.parse("#f00"))
        assertEquals(0x123456, TerminalColors.parse("#121234345656"))
        assertEquals(-1, TerminalColors.parse("red"))
        assertEquals(-1, TerminalColors.parse("rgb:zz/00/00"))
        assertEquals(-1, TerminalColors.parse("#12345"))
        assertEquals("rgb:1212/3434/5656", TerminalColors.format(0x123456))
    }

    @Test
    fun styleBitsDoNotOverlap() {
        var style = TextStyle.DEFAULT
        style = TextStyle.withForeground(style, TextStyle.rgb(255, 255, 255))
        style = TextStyle.withBackground(style, TextStyle.rgb(255, 255, 255))
        assertEquals(TextStyle.rgb(255, 255, 255), TextStyle.foreground(style))
        assertEquals(TextStyle.rgb(255, 255, 255), TextStyle.background(style))
        val flags = listOf(
            TextStyle.BOLD, TextStyle.FAINT, TextStyle.ITALIC, TextStyle.UNDERLINE, TextStyle.BLINK,
            TextStyle.INVERSE, TextStyle.INVISIBLE, TextStyle.STRIKETHROUGH, TextStyle.WIDE,
            TextStyle.WIDE_TRAILER, TextStyle.UNDERLINE_DOUBLE, TextStyle.UNDERLINE_CURLY,
        )
        for (flag in flags) {
            assertEquals(0L, style and flag, "flag overlaps a colour")
            assertEquals(1, java.lang.Long.bitCount(flag))
        }
        assertEquals(flags.size, flags.toSet().size)
        assertEquals(TextStyle.withBackground(TextStyle.DEFAULT, TextStyle.rgb(255, 255, 255)), TextStyle.eraseStyle(style or TextStyle.BOLD))
    }

    @Test
    fun schemesAreWellFormed() {
        assertTrue(ColorScheme.ALL.map { it.id }.toSet().size == ColorScheme.ALL.size)
        assertTrue(ColorScheme.DRACULA.isDark)
        assertTrue(!ColorScheme.SOLARIZED_LIGHT.isDark)
        assertEquals(ColorScheme.DEFAULT_DARK, ColorScheme.byId("no-such-scheme"))
        assertEquals(ColorScheme.NORD, ColorScheme.byId("nord"))
    }
}
