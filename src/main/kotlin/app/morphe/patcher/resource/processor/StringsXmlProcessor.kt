/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.copyAttributes
import app.morphe.patcher.resource.copyNamespaces
import app.morphe.patcher.resource.parseXml
import app.morphe.patcher.resource.writeXml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.logging.Logger

abstract internal class StringsXmlProcessor(
    internal val get: (String, String) -> File,
    internal val packageDirectories: Map<String, File>,
    internal val logString: String
) {
    private val logger = Logger.getLogger(this::class.java.name)

    fun process() {
        logger.info(logString)

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
        val tempFile = File(file.parentFile, file.name + ".tmp")

        file.parseXml { parser ->
            tempFile.writeXml { serializer ->
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
                                serializer.text(processString(text))
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

    abstract fun processString(text: String): String
}
