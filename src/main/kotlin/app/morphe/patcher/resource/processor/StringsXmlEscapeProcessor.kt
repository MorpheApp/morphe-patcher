/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.utf8Reader
import app.morphe.patcher.resource.utf8Writer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.logging.Logger

internal class StringsXmlEscapeProcessor(
    internal val get: (String, String) -> File,
    internal val packageDirectories: Map<String, File>,
) {

    private val logger = Logger.getLogger(StringsXmlEscapeProcessor::class.java.name)

    fun process() {
        logger.info("Escaping unpatched strings")

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
        val tempFile = File(file.parentFile, file.name + ".tmp")

        file.utf8Reader().use { reader ->
            parser.setInput(reader)

            tempFile.utf8Writer().use { writer ->
                val serializer: XmlSerializer = factory.newSerializer()
                serializer.setOutput(writer)
                serializer.startDocument("UTF-8", true)

                var eventType = parser.eventType
                var insideString = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {

                        XmlPullParser.START_TAG -> {
                            val tagName = parser.name

                            serializer.copyNamespaces(parser)
                            serializer.startTag(parser.namespace, tagName)
                            serializer.copyAttributes(parser)

                            if (tagName == "string") {
                                insideString = true
                            }
                        }

                        XmlPullParser.TEXT -> {
                            val text = parser.text
                            if (insideString) {
                                serializer.text(escapeString(text))
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
            }
        }

        // Replace original file
        file.delete()
        tempFile.renameTo(file)
    }

    /**
     * Single-pass escape (fast)
     */
    private fun escapeString(input: String): String {
        val sb = StringBuilder(input.length)

        for (ch in input) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                else -> {
                    val code = ch.code
                    if (code in 0x20..0x7E) {
                        sb.append(ch)
                    } else {
                        appendUnicode(sb, code)
                    }
                }
            }
        }

        return sb.toString()
    }

    private fun appendUnicode(sb: StringBuilder, code: Int) {
        sb.append("\\u")
        val hex = code.toString(16).uppercase()
        repeat(4 - hex.length) { sb.append('0') }
        sb.append(hex)
    }
}
