/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.PublicXmlManager
import com.reandroid.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.logging.Logger
import kotlin.jvm.java

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
        publicXmlManager.getPublicNode().setAttribute("package", newPackageName)

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
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(BufferedReader(FileReader(file)))

        val tempFile = File(file.parentFile, file.name + ".tmp")
        val writer = BufferedWriter(FileWriter(tempFile))
        val serializer = factory.newSerializer()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)

        var eventType = parser.eventType
        var currentTag: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    serializer.startTag(parser.namespace, currentTag)
                    for (i in 0 until parser.attributeCount) {
                        var value = parser.getAttributeValue(i)
                        if (regex.matches(value)) {
                            value = value.replace(originalPackageName, newPackageName)
                        }
                        serializer.attribute(
                            parser.getAttributeNamespace(i),
                            parser.getAttributeName(i),
                            value
                        )
                    }
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
        writer.flush()
        writer.close()

        file.delete()
        tempFile.renameTo(file)
    }
}
