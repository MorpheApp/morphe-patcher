/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.*
import app.morphe.patcher.util.FileUtils.safelyMoveTo
import com.reandroid.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.logging.Logger

/**
 * Rewrites references to a renamed package in resource XML. The declarations of the name itself,
 * in `public.xml` and `package.json`, are the caller's, see [renameDeclarations].
 */
internal class PackageRenamingProcessor(
    private val get: (String, String) -> File,
    private val packageDirectories: Map<String, File>,
    private val originalPackageName: String,
    private val newPackageName: String
) {
    private val logger = Logger.getLogger(PackageRenamingProcessor::class.java.name)
    private val regex = Regex("^[@?]$originalPackageName:.*")

    /**
     * Every resource XML of every package but the renamed one. The renamed package refers to its
     * own resources without a package name; other packages may name it.
     */
    fun resourceXmlFiles(): List<File> = buildList {
        otherPackageDirectories().forEach { rootDir ->
            rootDir.resolve("res").listFiles { it.isDirectory }?.forEach { dir ->
                dir.listFiles { it.extension == "xml" && it.name != "strings.xml" }?.forEach { add(it) }
            }
        }
    }

    /**
     * Updates the declarations of the package name: `public.xml` through [publicXmlManager] and
     * `package.json`.
     */
    fun renameDeclarations(publicXmlManager: PublicXmlManager) {
        if (originalPackageName == newPackageName) return
        publicXmlManager.changePackageName(newPackageName)
        get("package.json", originalPackageName).apply {
            val packageJson = JSONObject(this)
            packageJson.put("package_name", newPackageName)
            packageJson.write(this)
        }
    }

    /**
     * @param files The resource files whose references to the package are rewritten. Compiled
     * resources reference a package by id, so only files that are encoded again need this.
     * Files of the renamed package and strings files are ignored.
     * @return The files this changed.
     */
    fun process(files: Collection<File> = resourceXmlFiles()): Set<File> {
        if (originalPackageName == newPackageName) return emptySet()

        logger.info("Post-processing package name change")
        val otherPackageDirectories = otherPackageDirectories()
        return files.filterTo(mutableSetOf()) { file ->
            file.isFile && file.extension == "xml" && file.name != "strings.xml" &&
                    otherPackageDirectories.any { file.startsWith(it) } &&
                    processFile(file)
        }
    }

    private fun otherPackageDirectories() = packageDirectories.filter { it.key != originalPackageName }.values

    private fun processFile(file: File): Boolean {
        val tempFile = File(file.parentFile, file.name + ".tmp")
        var changed = false

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
                                    changed = true
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
                                changed = true
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

        if (changed) {
            tempFile.safelyMoveTo(file)
        } else {
            tempFile.delete()
        }
        return changed
    }
}
