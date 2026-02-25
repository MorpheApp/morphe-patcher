/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.inOrderTraverse
import app.morphe.patcher.util.Document
import java.io.File
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
                    Document(get("res/${dir.name}/${file.name}", resPackageName)).use { doc ->
                        doc.inOrderTraverse { element ->
                            if (element.nodeName == "string") {
                                val original = element.textContent
                                var escaped = original

                                // Escape backslash FIRST
                                escaped = escaped.replace("\\", "\\\\")

                                // Escape common special characters
                                escaped = escaped
                                    .replace("'", "\\'")
                                    .replace("\"", "\\\"")
                                    .replace("\n", "\\n")
                                    .replace("\t", "\\t")
                                    .replace("\r", "\\r")

                                // Escape non-ASCII characters as unicode
                                escaped = buildString {
                                    for (ch in escaped) {
                                        if (ch.code in 0x20..0x7E) {
                                            append(ch)
                                        } else {
                                            append("\\u%04X".format(ch.code))
                                        }
                                    }
                                }

                                if (original != escaped) {
                                    element.textContent = escaped
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}