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

    /** Contrast ratio between two colours as WCAG defines it: 1 is none, 21 is black on white. */
    private fun contrast(first: Int, second: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        fun luminance(rgb: Int) = 0.2126 * channel((rgb shr 16) and 0xFF) + 0.7152 * channel((rgb shr 8) and 0xFF) + 0.0722 * channel(rgb and 0xFF)
        val (lighter, darker) = listOf(luminance(first), luminance(second)).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun everySchemeIsReadable() {
        for (scheme in ColorScheme.ALL) {
            // The schemes that are this app's own have to be clearly readable. The well-known
            // ones keep their published colours, which are sometimes fainter on purpose.
            val own = scheme in listOf(ColorScheme.DEFAULT_DARK, ColorScheme.DEFAULT_LIGHT, ColorScheme.BLACK, ColorScheme.GREEN)
            val text = contrast(scheme.foreground, scheme.background)
            assertTrue(text >= if (own) 7.0 else 4.0, "${scheme.name}: text on background has a contrast of only $text")
            assertTrue(contrast(scheme.cursor, scheme.background) >= 3.0, "${scheme.name}: the cursor is hard to see")
            // The colours programs use for output. The two that are meant to match the
            // background are left out: the blacks of a dark scheme, the whites of a light one.
            for (index in if (scheme.isDark) (1..7) + (9..15) else (0..6) + (8..14)) {
                val ratio = contrast(scheme.ansi(index), scheme.background)
                assertTrue(ratio >= if (own) 3.0 else 2.0, "${scheme.name}: ANSI colour $index has a contrast of only $ratio")
            }
        }
    }

    @Test
    fun greenOnBlackIsGreenOnBlack() {
        val scheme = ColorScheme.byId("green")
        assertEquals("Green on Black", scheme.name)
        assertEquals(0x000000, scheme.background)
        val red = (scheme.foreground shr 16) and 0xFF
        val green = (scheme.foreground shr 8) and 0xFF
        val blue = scheme.foreground and 0xFF
        assertTrue(green > 2 * red && green > blue, "the text colour is a green")
        assertEquals(scheme.foreground, scheme.cursor)
        // Programs that ask for green get the same green; other colours stay what they are.
        assertEquals(scheme.foreground, scheme.ansi(2))
        assertTrue(((scheme.ansi(1) shr 16) and 0xFF) > 200, "red is still red")
        assertTrue(ColorScheme.ALL.indexOf(scheme) == ColorScheme.ALL.indexOf(ColorScheme.BLACK) + 1, "offered right after Pure Black")
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
