package io.github.evilgenius79.shh.terminal

/**
 * A cell's visual attributes packed into a single [Long] so that a screen line can keep them in a
 * primitive array.
 *
 * Layout: bits 0..25 foreground colour, bits 26..51 background colour, bits 52..63 attribute flags.
 *
 * A colour is 26 bits wide: `0` means "the default colour", `COLOR_INDEXED | n` selects palette
 * entry `n` (0..255) and `COLOR_RGB | 0xRRGGBB` is a direct 24 bit colour.
 */
public object TextStyle {
    /** Default foreground on default background with no attributes. */
    public const val DEFAULT: Long = 0L

    public const val COLOR_DEFAULT: Int = 0
    public const val COLOR_INDEXED: Int = 1 shl 24
    public const val COLOR_RGB: Int = 2 shl 24

    private const val COLOR_BITS = 26
    private const val COLOR_MASK: Long = (1L shl COLOR_BITS) - 1
    private const val FG_SHIFT = 0
    private const val BG_SHIFT = COLOR_BITS
    private const val FLAG_SHIFT = 2 * COLOR_BITS

    public const val BOLD: Long = 1L shl FLAG_SHIFT
    public const val FAINT: Long = 1L shl (FLAG_SHIFT + 1)
    public const val ITALIC: Long = 1L shl (FLAG_SHIFT + 2)
    public const val UNDERLINE: Long = 1L shl (FLAG_SHIFT + 3)
    public const val BLINK: Long = 1L shl (FLAG_SHIFT + 4)
    public const val INVERSE: Long = 1L shl (FLAG_SHIFT + 5)
    public const val INVISIBLE: Long = 1L shl (FLAG_SHIFT + 6)
    public const val STRIKETHROUGH: Long = 1L shl (FLAG_SHIFT + 7)

    /** The cell holds a double width character; the cell to its right is its [WIDE_TRAILER]. */
    public const val WIDE: Long = 1L shl (FLAG_SHIFT + 8)

    /** Right half of a double width character. Holds no text of its own. */
    public const val WIDE_TRAILER: Long = 1L shl (FLAG_SHIFT + 9)

    /** Underline drawn as a double line (SGR 21 / 4:2). Always combined with [UNDERLINE]. */
    public const val UNDERLINE_DOUBLE: Long = 1L shl (FLAG_SHIFT + 10)

    /** Underline drawn curly, dotted or dashed (SGR 4:3..4:5). Always combined with [UNDERLINE]. */
    public const val UNDERLINE_CURLY: Long = 1L shl (FLAG_SHIFT + 11)

    private const val FG_MASK: Long = COLOR_MASK shl FG_SHIFT
    private const val BG_MASK: Long = COLOR_MASK shl BG_SHIFT
    private const val WIDTH_FLAGS: Long = WIDE or WIDE_TRAILER

    @JvmStatic
    public fun foreground(style: Long): Int = ((style ushr FG_SHIFT) and COLOR_MASK).toInt()

    @JvmStatic
    public fun background(style: Long): Int = ((style ushr BG_SHIFT) and COLOR_MASK).toInt()

    @JvmStatic
    public fun withForeground(style: Long, color: Int): Long =
        (style and FG_MASK.inv()) or ((color.toLong() and COLOR_MASK) shl FG_SHIFT)

    @JvmStatic
    public fun withBackground(style: Long, color: Int): Long =
        (style and BG_MASK.inv()) or ((color.toLong() and COLOR_MASK) shl BG_SHIFT)

    @JvmStatic
    public fun has(style: Long, flag: Long): Boolean = (style and flag) != 0L

    /** The style used for erased cells: only the background colour survives (back colour erase). */
    @JvmStatic
    public fun eraseStyle(style: Long): Long = style and BG_MASK

    /** [style] without the flags that describe the cell's width rather than its look. */
    @JvmStatic
    public fun withoutWidth(style: Long): Long = style and WIDTH_FLAGS.inv()

    @JvmStatic
    public fun indexed(index: Int): Int = COLOR_INDEXED or (index and 0xFF)

    @JvmStatic
    public fun rgb(red: Int, green: Int, blue: Int): Int =
        COLOR_RGB or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF)

    @JvmStatic
    public fun isDefaultColor(color: Int): Boolean = color == COLOR_DEFAULT

    @JvmStatic
    public fun isIndexedColor(color: Int): Boolean = (color and (3 shl 24)) == COLOR_INDEXED

    @JvmStatic
    public fun isRgbColor(color: Int): Boolean = (color and (3 shl 24)) == COLOR_RGB

    /** Palette index of an indexed colour, or the 24 bit value of an RGB colour. */
    @JvmStatic
    public fun colorValue(color: Int): Int = color and 0xFFFFFF
}
