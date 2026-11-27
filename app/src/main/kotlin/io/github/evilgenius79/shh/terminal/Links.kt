package io.github.evilgenius79.shh.terminal

/** Recognises web addresses in text taken from the terminal. */
object Links {
    private val WEB_ADDRESS = Regex("""^(https?://|www\.)[^\s<>"'`]+$""", RegexOption.IGNORE_CASE)
    private val CLOSERS = mapOf(')' to '(', ']' to '[', '}' to '{', '>' to '<')

    /**
     * [text] as an address to open, or null when it is not a web address. Only http and https
     * are accepted: nothing printed by a server gets to start anything else on the phone.
     *
     * Punctuation that a sentence puts after an address is not part of it, and neither is a
     * closing bracket whose opening one is not in the address.
     */
    fun asLink(text: String): String? {
        var candidate = text.trim()
        while (candidate.isNotEmpty()) {
            val last = candidate.last()
            val opener = CLOSERS[last]
            val stray = when {
                last in ".,;:!?'\"" -> true
                opener != null -> candidate.count { it == opener } < candidate.count { it == last }
                else -> false
            }
            if (!stray) break
            candidate = candidate.dropLast(1)
        }
        if (!WEB_ADDRESS.matches(candidate)) return null
        // An address needs more than its scheme.
        val rest = candidate.substringAfter("://", candidate.substringAfter("www.", ""))
        if (rest.isEmpty()) return null
        return if (candidate.startsWith("www.", ignoreCase = true)) "https://$candidate" else candidate
    }
}
