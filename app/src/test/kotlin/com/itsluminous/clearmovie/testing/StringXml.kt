package com.itsluminous.clearmovie.testing

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** One `<string>`, `<plurals>` or `<string-array>` entry of a resource file. */
data class StringEntry(
    val file: String,
    val tag: String,
    val name: String,
    val translatable: Boolean,
    /** The text of a `<string>`, or every `<item>` of a `<plurals>` / `<string-array>`. */
    val values: List<String>,
    /** `quantity` of each `<item>` of a `<plurals>`, empty otherwise. */
    val quantities: List<String>,
)

/** Reads string-like entries from Android resource XML. */
object StringXml {
    private val stringTags = setOf("string", "plurals", "string-array")

    fun entries(file: File): List<StringEntry> {
        val root =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .documentElement
        val children = root.childNodes
        return (0 until children.length)
            .mapNotNull { children.item(it) as? Element }
            .filter { it.tagName in stringTags }
            .map { element ->
                val items = element.childElements("item")
                StringEntry(
                    file = file.path,
                    tag = element.tagName,
                    name = element.getAttribute("name"),
                    translatable = element.getAttribute("translatable") != "false",
                    values = if (element.tagName == "string") listOf(element.textContent) else items.map { it.textContent },
                    quantities = if (element.tagName == "plurals") items.map { it.getAttribute("quantity") } else emptyList(),
                )
            }
    }

    /** Entries from every `strings*.xml` file in [valuesDir]. */
    fun entriesIn(valuesDir: File): List<StringEntry> =
        valuesDir
            .listFiles { f: File -> f.name.startsWith("strings") && f.extension == "xml" }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap(::entries)

    private fun Element.childElements(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }
}
