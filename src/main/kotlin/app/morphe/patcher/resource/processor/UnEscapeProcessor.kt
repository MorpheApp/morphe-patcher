/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.inOrderTraverse
import app.morphe.patcher.util.Document
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.logging.Logger
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal class UnEscapeProcessor(
    internal val get: (String, String) -> File,
    internal val packageDirectories: Map<String, File>,
) {
    private val logger = Logger.getLogger(UnEscapeProcessor::class.java.name)

    private val escapedUnicodeRegex = Regex("\\\\u([0-9a-fA-F]{4})")

    fun process() {
        logger.info("Running string escape processing")

        packageDirectories.forEach { (resPackageName, rootDir) ->
            rootDir.resolve("res").listFiles { it.isDirectory }?.forEach { dir ->
                // TODO Strings declared in arrays.xml may also need unescaping of string literals.
                dir.listFiles { it.name == "strings.xml" }?.forEach { file ->
                    val rawXml = file.readText()
                    // Sanitize and remove invalid XML before parsing.
                    val sanitizedXml = sanitizeXmlText(rawXml)
                    val inputStream = ByteArrayInputStream(
                        sanitizedXml.toByteArray(StandardCharsets.UTF_8)
                    )

                    Document(inputStream).use { doc ->
                        doc.inOrderTraverse { element ->
                            if (element.nodeName == "string") {
                                val textContent = element.textContent
                                var unescaped = textContent

                                // Encoder handles all strings like raw strings and
                                // opening/closing quotations are not needed.
                                if (unescaped.startsWith('"') && unescaped.endsWith('"')) {
                                    unescaped = unescaped.substring(1, unescaped.length - 1)
                                }

                                unescaped = unescaped
                                    .replace("\\'", "'")
                                    .replace("\\\"", "\"")
                                    .replace("\\n", "\n")
                                    .replace("\\t", "\t")
                                    .replace("\\r", "\r")
                                    .replace(escapedUnicodeRegex) {
                                        it.groupValues[1].toInt(16).toChar().toString()
                                    }
                                    .replace("\\\\", "\\")

                                if (textContent != unescaped) {
                                    element.textContent = unescaped
                                }
                            }
                        }
                        file.writeText(doc.toXmlString())
                    }
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

