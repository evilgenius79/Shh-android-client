package io.github.evilgenius79.shh.terminal

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import java.io.IOException
import java.io.InputStream

/** A font file that cannot be used for the terminal. The message says why, for the user. */
class FontException(message: String) : Exception(message)

/**
 * The font file the user chose for the terminal in place of the bundled typeface. The app keeps
 * its own copy, so the font stays available whatever happens to the file it was taken from.
 */
object TerminalFonts {
    private const val DIRECTORY = "fonts"

    /** Far above any font for a terminal, and enough to stop a mistaken choice filling the storage. */
    const val MAX_BYTES = 32L * 1024 * 1024

    /** Characters of very different shapes. In a monospaced font they are all equally wide. */
    private const val PROBE = "iWm.0@"

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)

    /** Where the font stored under [name] is. */
    fun file(context: Context, name: String): File = File(directory(context), File(name).name)

    /** The typeface in [file], or null when the file is missing or not a font Android can read. */
    fun load(file: File): Typeface? {
        if (!file.isFile) return null
        return try {
            Typeface.Builder(file).build()
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Why [typeface] is no good for a terminal, or null when it is fine. */
    fun problemWith(typeface: Typeface): String? {
        val paint = Paint()
        paint.typeface = typeface
        paint.textSize = 100f
        val widths = PROBE.map { paint.measureText(it.toString()) }
        return when {
            widths.min() <= 0f -> "This font has no shapes for ordinary letters and digits."
            widths.max() - widths.min() > 0.5f ->
                "The characters of this font differ in width. A terminal needs a monospaced font, in which they are all equally wide."
            else -> null
        }
    }

    /**
     * Makes what [input] delivers the terminal's font and returns the name it is stored under,
     * for [file]. Any font stored before is removed. A file that is not a usable font is refused
     * with a [FontException], and whatever was stored before then stays.
     */
    @Throws(FontException::class, IOException::class)
    fun install(context: Context, input: InputStream): String {
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("The app's font folder could not be created")
        val name = "font-${System.currentTimeMillis()}"
        val target = File(directory, name)
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) throw FontException("This file is larger than ${MAX_BYTES / (1024 * 1024)} MB, which no font for a terminal is.")
                    output.write(buffer, 0, count)
                }
            }
            val typeface = load(target) ?: throw FontException("This file is not a font Android can read. Choose a .ttf or .otf file.")
            problemWith(typeface)?.let { throw FontException(it) }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        directory.listFiles()?.forEach { if (it.name != name) it.delete() }
        return name
    }

    /** Removes the stored font: the terminal is back to the bundled typeface. */
    fun remove(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }
}
