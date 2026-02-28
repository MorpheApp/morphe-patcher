/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.logging.Logger

internal class StringsXmlUnEscapeProcessor(
    internal val get: (String, String) -> File,
    internal val packageDirectories: Map<String, File>,
) {

    private val logger = Logger.getLogger(StringsXmlUnEscapeProcessor::class.java.name)

    fun process() {
        logger.info("Unescaping strings")

        packageDirectories.forEach { (resPackageName, rootDir) ->
            rootDir.resolve("res").listFiles { it.isDirectory }?.forEach { dir ->
                // TODO Strings declared in arrays.xml may also need unescaping of string literals.
                dir.listFiles { it.name == "strings.xml" }?.forEach { file ->
                    val path = "res/${dir.name}/${file.name}"
                    logger.fine { "Processing $path" }

                    val targetFile = get(path, resPackageName)
                    processFile(targetFile)
                }
            }
        }
    }

    private fun processFile(file: File) {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true

        val parser = factory.newPullParser()
        val reader = BufferedReader(FileReader(file, Charsets.UTF_8))
        parser.setInput(reader)

        val tempFile = File(file.parentFile, file.name + ".tmp")
        val writer = BufferedWriter(FileWriter(tempFile, Charsets.UTF_8))

        val serializer: XmlSerializer = factory.newSerializer()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)

        var eventType = parser.eventType
        var insideString = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {

                XmlPullParser.START_TAG -> {
                    val tagName = parser.name
                    serializer.startTag(parser.namespace, tagName)

                    for (i in 0 until parser.attributeCount) {
                        serializer.attribute(
                            parser.getAttributeNamespace(i),
                            parser.getAttributeName(i),
                            parser.getAttributeValue(i)
                        )
                    }

                    if (tagName == "string") {
                        insideString = true
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text
                    if (insideString) {
                        serializer.text(unescapeString(text))
                    } else {
                        serializer.text(text)
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tagName = parser.name
                    serializer.endTag(parser.namespace, tagName)

                    if (tagName == "string") {
                        insideString = false
                    }
                }

                XmlPullParser.CDSECT -> {
                    serializer.cdsect(parser.text)
                }

                XmlPullParser.COMMENT -> {
                    serializer.comment(parser.text)
                }

                XmlPullParser.IGNORABLE_WHITESPACE -> {
                    serializer.ignorableWhitespace(parser.text)
                }
            }

            eventType = parser.next()
        }

        serializer.endDocument()
        writer.flush()
        writer.close()
        reader.close()

        // Replace original file
        file.delete()
        tempFile.renameTo(file)
    }

    /**
     * Single-pass unescape of string content.
     */
    private fun unescapeString(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length) {
                when (input[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    '"'  -> { sb.append('"'); i += 2 }
                    'n'  -> { sb.append('\n'); i += 2 }
                    't'  -> { sb.append('\t'); i += 2 }
                    'r'  -> { sb.append('\r'); i += 2 }
                    'u'  -> {
                        // \uXXXX
                        if (i + 5 < input.length) {
                            val hex = input.substring(i + 2, i + 6)
                            try {
                                val code = hex.toInt(16)
                                sb.append(code.toChar())
                                i += 6
                            } catch (_: NumberFormatException) {
                                // Invalid escape → leave as-is
                                sb.append("\\u")
                                i += 2
                            }
                        } else {
                            sb.append("\\u")
                            i += 2
                        }
                    }
                    else -> {
                        // Unknown escape → keep as-is
                        sb.append(c)
                        i++
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }

        // Strip surrounding quotes if present
        if (sb.length >= 2 && sb[0] == '"' && sb[sb.length - 1] == '"') {
            return sb.substring(1, sb.length - 1)
        }

        return sb.toString()
    }
}
