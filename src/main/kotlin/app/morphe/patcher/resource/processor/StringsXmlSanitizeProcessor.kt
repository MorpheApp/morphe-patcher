/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.util.Document
import java.io.File
import java.io.StringWriter
import java.util.logging.Logger
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal class StringsXmlSanitizeProcessor(
    internal val get: (String, String) -> File,
    internal val packageDirectories: Map<String, File>,
) {
    private val logger = Logger.getLogger(StringsXmlSanitizeProcessor::class.java.name)

    fun process() {
        logger.info("Sanitizing unpatched strings")

        packageDirectories.forEach { (_, rootDir) ->
            rootDir.resolve("res").listFiles { it.isDirectory }?.forEach { dir ->
                dir.listFiles { it.name == "strings.xml" }?.forEach { file ->
                    val rawXml = file.readText(Charsets.UTF_8)
                    file.writeText(sanitizeXmlText(rawXml), Charsets.UTF_8)
                }
            }
        }
    }
}


/**
 * Clean both forms of invalid XML characters:
 * 1. Remove invalid numeric character references (e.g., &#65535;), which would
 *    otherwise cause the XML parser to fail before the document can be loaded.
 * 2. Remove any literal invalid Unicode characters that may appear directly in
 *    the file (e.g., U+FFFF), ensuring the resulting text always conforms to
 *    XML 1.0's allowed character ranges.
 */
internal fun sanitizeXmlText(input: String): String {
    fun isValidXmlChar(code: Int): Boolean =
        code == 0x9 ||
                code == 0xA ||
                code == 0xD ||
                (code in 0x20..0xD7FF) ||
                (code in 0xE000..0xFFFD) ||
                (code in 0x10000..0x10FFFF)

    // Remove invalid numeric character references like &#65535;
    val cleanedEntities = input.replace(Regex("&#(\\d+);")) { match ->
        val code = match.groupValues[1].toInt()
        if (isValidXmlChar(code)) match.value else ""
    }

    // Remove invalid literal unicode.
    return cleanedEntities.filter { ch -> isValidXmlChar(ch.code) }
}

internal fun Document.toXmlString(): String {
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")

    val writer = StringWriter()
    transformer.transform(DOMSource(this), StreamResult(writer))
    return writer.toString()
}
