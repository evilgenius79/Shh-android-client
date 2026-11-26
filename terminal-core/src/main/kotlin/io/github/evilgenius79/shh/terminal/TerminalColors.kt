package io.github.evilgenius79.shh.terminal

/**
 * A named set of terminal colours: default foreground/background, cursor and the 16 ANSI colours.
 * All colours are `0xRRGGBB`.
 */
public class ColorScheme(
    public val id: String,
    public val name: String,
    public val foreground: Int,
    public val background: Int,
    public val cursor: Int,
    ansi: IntArray,
) {
    private val ansiColors: IntArray = ansi.copyOf()

    init {
        require(ansiColors.size == 16) { "A colour scheme needs exactly 16 ANSI colours" }
    }

    public fun ansi(index: Int): Int = ansiColors[index]

    /** True when the background is dark, which decides how the surrounding UI is tinted. */
    public val isDark: Boolean get() = TerminalColors.luminance(background) < 0.5

    public companion object {
        public val DEFAULT_DARK: ColorScheme = ColorScheme(
            "default-dark", "Default Dark", 0xD3D7CF, 0x101214, 0xD3D7CF,
            intArrayOf(
                0x2E3436, 0xCC0000, 0x4E9A06, 0xC4A000, 0x3465A4, 0x75507B, 0x06989A, 0xD3D7CF,
                0x555753, 0xEF2929, 0x8AE234, 0xFCE94F, 0x729FCF, 0xAD7FA8, 0x34E2E2, 0xEEEEEC,
            ),
        )

        public val DEFAULT_LIGHT: ColorScheme = ColorScheme(
            "default-light", "Default Light", 0x2E3436, 0xFAFAFA, 0x2E3436,
            intArrayOf(
                0x2E3436, 0xCC0000, 0x4E9A06, 0xC4A000, 0x3465A4, 0x75507B, 0x06989A, 0xD3D7CF,
                0x555753, 0xEF2929, 0x8AE234, 0xFCE94F, 0x729FCF, 0xAD7FA8, 0x34E2E2, 0xEEEEEC,
            ),
        )

        public val BLACK: ColorScheme = ColorScheme(
            "black", "Pure Black", 0xE5E5E5, 0x000000, 0xE5E5E5,
            intArrayOf(
                0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
                0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
            ),
        )

        public val DRACULA: ColorScheme = ColorScheme(
            "dracula", "Dracula", 0xF8F8F2, 0x282A36, 0xF8F8F2,
            intArrayOf(
                0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
                0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF,
            ),
        )

        public val SOLARIZED_DARK: ColorScheme = ColorScheme(
            "solarized-dark", "Solarized Dark", 0x839496, 0x002B36, 0x93A1A1,
            intArrayOf(
                0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
                0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
            ),
        )

        public val SOLARIZED_LIGHT: ColorScheme = ColorScheme(
            "solarized-light", "Solarized Light", 0x657B83, 0xFDF6E3, 0x586E75,
            intArrayOf(
                0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
                0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
            ),
        )

        public val GRUVBOX_DARK: ColorScheme = ColorScheme(
            "gruvbox-dark", "Gruvbox Dark", 0xEBDBB2, 0x282828, 0xEBDBB2,
            intArrayOf(
                0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984,
                0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F, 0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
            ),
        )

        public val NORD: ColorScheme = ColorScheme(
            "nord", "Nord", 0xD8DEE9, 0x2E3440, 0xD8DEE9,
            intArrayOf(
                0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
                0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4,
            ),
        )

        public val ONE_DARK: ColorScheme = ColorScheme(
            "one-dark", "One Dark", 0xABB2BF, 0x282C34, 0x528BFF,
            intArrayOf(
                0x282C34, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xABB2BF,
                0x5C6370, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xFFFFFF,
            ),
        )

        public val TOKYO_NIGHT: ColorScheme = ColorScheme(
            "tokyo-night", "Tokyo Night", 0xC0CAF5, 0x1A1B26, 0xC0CAF5,
            intArrayOf(
                0x15161E, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xA9B1D6,
                0x414868, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xC0CAF5,
            ),
        )

        public val MONOKAI: ColorScheme = ColorScheme(
            "monokai", "Monokai", 0xF8F8F2, 0x272822, 0xF8F8F0,
            intArrayOf(
                0x272822, 0xF92672, 0xA6E22E, 0xF4BF75, 0x66D9EF, 0xAE81FF, 0xA1EFE4, 0xF8F8F2,
                0x75715E, 0xF92672, 0xA6E22E, 0xF4BF75, 0x66D9EF, 0xAE81FF, 0xA1EFE4, 0xF9F8F5,
            ),
        )

        /** Every built in scheme, in the order they are offered to the user. */
        public val ALL: List<ColorScheme> = listOf(
            DEFAULT_DARK, BLACK, DEFAULT_LIGHT, DRACULA, GRUVBOX_DARK, MONOKAI, NORD, ONE_DARK,
            SOLARIZED_DARK, SOLARIZED_LIGHT, TOKYO_NIGHT,
        )

        public fun byId(id: String?): ColorScheme = ALL.firstOrNull { it.id == id } ?: DEFAULT_DARK
    }
}

/**
 * The live colour table of one terminal: a 256 entry palette plus the default foreground,
 * background and cursor colours. Starts out as a [ColorScheme] and can then be changed by the
 * remote side through OSC 4/10/11/12.
 */
public class TerminalColors(scheme: ColorScheme = ColorScheme.DEFAULT_DARK) {
    private val palette = IntArray(256)

    public var scheme: ColorScheme = scheme
        private set

    public var foreground: Int = 0
    public var background: Int = 0
    public var cursor: Int = 0

    init {
        reset()
    }

    /** Switches to another scheme, dropping every colour the remote side had customised. */
    public fun applyScheme(newScheme: ColorScheme) {
        scheme = newScheme
        reset()
    }

    /** Restores every colour to the current [scheme]. */
    public fun reset() {
        for (i in 0 until 256) palette[i] = defaultPaletteColor(i)
        foreground = scheme.foreground
        background = scheme.background
        cursor = scheme.cursor
    }

    public fun paletteColor(index: Int): Int = palette[index and 0xFF]

    public fun setPaletteColor(index: Int, rgb: Int) {
        if (index in 0..255) palette[index] = rgb and 0xFFFFFF
    }

    public fun resetPaletteColor(index: Int) {
        if (index in 0..255) palette[index] = defaultPaletteColor(index)
    }

    private fun defaultPaletteColor(index: Int): Int = when {
        index < 16 -> scheme.ansi(index)
        index < 232 -> {
            val n = index - 16
            (CUBE_LEVELS[n / 36] shl 16) or (CUBE_LEVELS[(n / 6) % 6] shl 8) or CUBE_LEVELS[n % 6]
        }
        else -> {
            val level = 8 + 10 * (index - 232)
            (level shl 16) or (level shl 8) or level
        }
    }

    /**
     * Resolves a colour taken from a packed [TextStyle] to `0xRRGGBB`.
     *
     * @param isForeground decides which default colour an unset colour falls back to.
     */
    public fun resolve(color: Int, isForeground: Boolean): Int = when {
        TextStyle.isDefaultColor(color) -> if (isForeground) foreground else background
        TextStyle.isIndexedColor(color) -> palette[TextStyle.colorValue(color) and 0xFF]
        else -> TextStyle.colorValue(color)
    }

    public companion object {
        private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

        /** Relative luminance in 0..1 of an `0xRRGGBB` colour (Rec. 709 weights, no gamma). */
        @JvmStatic
        public fun luminance(rgb: Int): Double {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        }

        /**
         * Parses the colour specifications accepted by OSC 4/10/11/12: `#RGB`, `#RRGGBB`,
         * `#RRRGGGBBB`, `#RRRRGGGGBBBB` and `rgb:R/G/B` with 1 to 4 hex digits per channel.
         * Returns `0xRRGGBB` or -1 when the text is not a colour.
         */
        @JvmStatic
        public fun parse(spec: String): Int {
            val text = spec.trim()
            try {
                if (text.startsWith("#")) {
                    val hex = text.substring(1)
                    if (hex.isEmpty() || hex.length % 3 != 0 || hex.length > 12) return -1
                    val digits = hex.length / 3
                    val r = scale(hex.substring(0, digits))
                    val g = scale(hex.substring(digits, 2 * digits))
                    val b = scale(hex.substring(2 * digits))
                    if (r < 0 || g < 0 || b < 0) return -1
                    return (r shl 16) or (g shl 8) or b
                }
                if (text.startsWith("rgb:", ignoreCase = true)) {
                    val parts = text.substring(4).split('/')
                    if (parts.size != 3) return -1
                    val r = scale(parts[0])
                    val g = scale(parts[1])
                    val b = scale(parts[2])
                    if (r < 0 || g < 0 || b < 0) return -1
                    return (r shl 16) or (g shl 8) or b
                }
            } catch (_: NumberFormatException) {
                return -1
            }
            return -1
        }

        /** Scales a 1..4 digit hex channel value to 8 bits. */
        private fun scale(hex: String): Int {
            if (hex.isEmpty() || hex.length > 4) return -1
            for (ch in hex) if (Character.digit(ch, 16) < 0) return -1
            val value = hex.toInt(16)
            val max = (1 shl (4 * hex.length)) - 1
            return (value * 255 + max / 2) / max
        }

        /** Formats a colour the way xterm answers colour queries: `rgb:RRRR/GGGG/BBBB`. */
        @JvmStatic
        public fun format(rgb: Int): String {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return "rgb:" + hex4(r) + "/" + hex4(g) + "/" + hex4(b)
        }

        private fun hex4(channel: Int): String {
            val two = Integer.toHexString(channel or 0x100).substring(1)
            return two + two
        }
    }
}
