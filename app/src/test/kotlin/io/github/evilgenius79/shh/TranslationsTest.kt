package io.github.evilgenius79.shh

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The translations are held against the English texts they translate: nothing missing, nothing
 * left over, and the same things filled in. What this cannot judge is whether a translation is
 * a good one.
 */
class TranslationsTest {
    private val resources = File("src/main/res")

    /** One text: a string, or one form of a plural, by a name such as `hosts_count#other`. */
    private fun texts(directory: File): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        val files = directory.listFiles { file -> file.name.startsWith("strings") && file.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val nodes = root.childNodes
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                if (element.getAttribute("translatable") == "false") continue
                val name = element.getAttribute("name")
                when (element.tagName) {
                    "string" -> assertTrue(found.put(name, element.textContent) == null, "$name is defined twice (${file.name})")
                    "plurals" -> {
                        val items = element.getElementsByTagName("item")
                        for (item in 0 until items.length) {
                            val form = items.item(item) as Element
                            val key = "$name#${form.getAttribute("quantity")}"
                            assertTrue(found.put(key, form.textContent) == null, "$key is defined twice (${file.name})")
                        }
                    }
                }
            }
        }
        return found
    }

    /** What gets filled into a text: `%1$s`, `%2$d` and the like, in order of their numbers. */
    private fun placeholders(text: String): List<String> =
        Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]").findAll(text.replace("%%", "")).map { it.value }.sorted().toList()

    private val english: Map<String, String> by lazy { texts(File(resources, "values")) }

    /** The folders with translations, by language: `de`, `pt-rBR`, ... */
    private val translations: Map<String, File> by lazy {
        resources.listFiles { file -> file.isDirectory && file.name.startsWith("values-") }.orEmpty()
            .filter { directory -> directory.list().orEmpty().any { it.startsWith("strings") } }
            .associateBy { it.name.removePrefix("values-") }
            .toSortedMap()
    }

    @Test
    fun theEnglishTextsAreInOrder() {
        assertTrue(english.size > 10, "found only ${english.size} texts in ${resources.absolutePath}/values")
        for ((name, text) in english) {
            assertTrue(text.isNotBlank(), "$name is empty")
            val numbered = placeholders(text)
            assertTrue(numbered.size <= 1 || numbered.all { '$' in it }, "$name fills in several things and has to number them, so that translations can reorder them: $text")
        }
        for (name in english.keys.filter { '#' in it }.map { it.substringBefore('#') }.distinct()) {
            assertTrue("$name#other" in english, "the plural $name lacks the form for \"other\", which every language needs")
        }
    }

    @Test
    fun everyTranslationHasEveryTextAndFillsInTheSameThings() {
        val problems = ArrayList<String>()
        for ((language, directory) in translations) {
            val translated = texts(directory)
            // Plurals have other forms in other languages, so they are compared by name.
            fun names(all: Map<String, String>) = all.keys.map { it.substringBefore('#') }.toSet()
            for (missing in names(english) - names(translated)) problems += "$language: $missing is not translated"
            for (stale in names(translated) - names(english)) problems += "$language: $stale translates a text that is not there any more"
            for ((name, text) in translated) {
                val plain = name.substringBefore('#')
                if (text.isBlank()) problems += "$language: $name is empty"
                if ('#' in name) {
                    if ("$plain#other" !in translated) problems += "$language: the plural $plain lacks the form for \"other\""
                    // A form for one thing may leave the number out; it may not fill in anything new.
                    val allowed = placeholders(english["$plain#other"].orEmpty())
                    if (!allowed.containsAll(placeholders(text))) problems += "$language: $name fills in ${placeholders(text)}, the English text $allowed"
                } else {
                    val wanted = placeholders(english[name] ?: continue)
                    if (placeholders(text) != wanted) problems += "$language: $name fills in ${placeholders(text)}, the English text $wanted"
                }
            }
        }
        assertEquals(emptyList(), problems.distinct(), problems.distinct().joinToString("\n"))
    }

    @Test
    fun theLanguagesOfferedToTheSystemAreTheOnesThatAreTranslated() {
        val config = File(resources, "xml/locales_config.xml")
        if (translations.isEmpty() && !config.exists()) return
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(config).documentElement
        val offered = ArrayList<String>()
        val nodes = root.getElementsByTagName("locale")
        for (index in 0 until nodes.length) {
            offered += (nodes.item(index) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name")
        }
        // "pt-rBR" as a folder is "pt-BR" as a language tag.
        val translated = translations.keys.map { it.replace("-r", "-") }
        assertEquals((listOf("en") + translated).sorted(), offered.sorted())
    }
}
