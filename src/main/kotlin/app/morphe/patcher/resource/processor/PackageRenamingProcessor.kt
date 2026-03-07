/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.*
import com.reandroid.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.logging.Logger

internal class PackageRenamingProcessor(
    private val get: (String, String) -> File,
    private val publicXmlManager: PublicXmlManager,
    private val packageDirectories: Map<String, File>,
    private val originalPackageName: String,
    private val newPackageName: String
) {
    private val logger = Logger.getLogger(PackageRenamingProcessor::class.java.name)
    private val regex = Regex("^[@?]$originalPackageName:.*")

    fun process() {
        if (originalPackageName == newPackageName) return

        logger.info("Post-processing package name change")

        // Update public.xml package
        publicXmlManager.changePackageName(newPackageName)

        // Update package.json
        get("package.json", originalPackageName).apply {
            val packageJson = JSONObject(this)
            packageJson.put("package_name", newPackageName)
            packageJson.write(this)
        }

        // Process all other XMLs in resource bundles
        packageDirectories.filter { it.key != originalPackageName }.forEach { (resPackageName, rootDir) ->
            rootDir.resolve("res").listFiles { it.isDirectory }?.forEach { dir ->
                dir.listFiles { it.extension == "xml" && it.name != "strings.xml" }?.forEach { file ->
                    processFile(file)
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

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            serializer.copyNamespaces(parser)
                            serializer.startTag(parser.namespace, parser.name)
                            serializer.copyAttributes(parser, attributeMapper = { ns, name, value ->
                                var newValue = value
                                if (regex.matches(value)) {
                                    newValue = value.replace(originalPackageName, newPackageName)
                                }
                                Triple(ns, name, newValue)
                            })
                        }
                        XmlPullParser.END_TAG -> {
                            serializer.endTag(parser.namespace, parser.name)
                        }
                        XmlPullParser.TEXT -> {
                            var text = parser.text
                            if (regex.matches(text)) {
                                text = text.replace(originalPackageName, newPackageName)
                            }
                            serializer.text(text)
                        }
                        XmlPullParser.CDSECT -> serializer.cdsect(parser.text)
                        XmlPullParser.COMMENT -> serializer.comment(parser.text)
                        XmlPullParser.IGNORABLE_WHITESPACE -> serializer.ignorableWhitespace(parser.text)
                    }
                    eventType = parser.next()
                }

                serializer.endDocument()
            }
        }

        file.delete()
        tempFile.renameTo(file)
    }
}
