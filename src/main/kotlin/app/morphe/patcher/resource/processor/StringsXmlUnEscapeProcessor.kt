/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.inOrderTraverse
import app.morphe.patcher.util.Document
import java.io.File
import java.util.logging.Logger

internal class StringsXmlUnEscapeProcessor(
    internal val get: (String, String) -> File,
    internal val packageDirectories: Map<String, File>,
) {
    private val logger = Logger.getLogger(StringsXmlUnEscapeProcessor::class.java.name)

    private val escapedUnicodeRegex = Regex("\\\\u([0-9a-fA-F]{4})")

    fun process() {
        logger.info("Running string escape processing")

        packageDirectories.forEach { (resPackageName, rootDir) ->
            rootDir.resolve("res").listFiles { it.isDirectory }?.forEach { dir ->
                // TODO Strings declared in arrays.xml may also need unescaping of string literals.
                dir.listFiles { it.name == "strings.xml" }?.forEach { file ->
                    Document(get("res/${dir.name}/${file.name}", resPackageName)).use { doc ->
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
                    }
                }
            }
        }
    }
}

