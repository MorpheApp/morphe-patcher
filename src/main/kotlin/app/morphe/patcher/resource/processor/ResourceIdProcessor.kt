/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.PublicXmlManager
import app.morphe.patcher.resource.fileResourceTypes
import app.morphe.patcher.resource.forEachAttribute
import app.morphe.patcher.resource.parseXml
import app.morphe.patcher.resource.preOrderTraverse
import app.morphe.patcher.resource.resourceToTagOverrideMapping
import app.morphe.patcher.util.Document
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.logging.Logger

internal class ResourceIdProcessor(
    internal val get: (path: String) -> File,
    internal val publicIdManager: PublicXmlManager,
    internal val modifiedResResources: Set<File>,
    private val listFiles: (directory: File) -> Array<File>? = File::listFiles,
) {
    private val logger = Logger.getLogger(ResourceIdProcessor::class.java.name)

    fun process() {
        logger.info("Generating new resource IDs")

        // New ids are numbered in the order resources are seen here, so every listing is sorted.
        // Directory listing order is not stable on every file system (Android's f2fs for one),
        // and an unsorted walk gives the same resource a different id from run to run.
        val resDirectories = (listFiles(get("res")) ?: throw PatchException("Resource directory not found"))
            .filter { it.isDirectory }
            .sortedBy { it.name }

        // Find all new ID declarations in XML files so we can create a corresponding entry in ids.xml
        // They will get added to public.xml later
        Document(get("res/values/ids.xml")).use { idDoc ->
            val idNode = idDoc.getElementsByTagName("resources").item(0)
                ?: throw IllegalStateException("ids.xml is missing the <resources> root element.")

            modifiedResResources
                .filter { it.exists() && it.extension == "xml" }
                .sortedBy { it.invariantSeparatorsPath }
                .forEach { file ->
                    // The document parser detects the encoding of a file that is not UTF-8, the pull parser does not.
                    val declarations = if (file.isUtf16()) null else readDeclarations(file)
                    if (declarations == null || declarations.needsRewrite) {
                        processIdAndAttrDeclarations(file, idNode)
                        // The rewrite changes attribute values, so the declarations are read again.
                        createPublicIdsFromValuesXml(file)
                    } else {
                        declarations.createPublicIds()
                    }
                }
        }

        createPublicIdsFromValuesXml(get("res/values/ids.xml"))

        // Ensure every file-based resource has a public ID. ARSCLib can decode synthetic inline-AAPT
        // resources which are not initially present in public.xml, so every file is enumerated, not only
        // the ones patches changed. Their names start with '$', which sorts before any name a patch adds.
        for (type in fileResourceTypes) {
            val directories = resDirectories.filter { it.name.startsWith(type) }
            for (dir in directories) {
                val files = listFiles(dir) ?: continue
                for (file in files.filter { it.isFile }.sortedBy { it.name }) {
                    publicIdManager.createPublicId(type, file.nameWithoutExtension)
                }
            }
        }
    }

    private fun processIdAndAttrDeclarations(file: File, idNode: Node): Set<String> {
        val createdIds = mutableSetOf<String>()
        Document(file).use { doc ->
            doc.documentElement.preOrderTraverse { element ->
                val idString = element.getAttribute("android:id")
                if (idString.startsWith("@+id/")) {
                    logger.fine { "Adding $idString to ids.xml" }
                    val idName = idString.substring(5)

                    val item = idNode.ownerDocument.createElement("id")
                    item.setAttribute("name", idName)
                    idNode.appendChild(item)

                    element.setAttribute("android:id", "@id/$idName")
                    createdIds += "@id/$idName"
                }

                // Expand shorthand theme attribute references in all attributes.
                // e.g. ?someValue -> ?attr/someValue, ?android:someValue -> ?android:attr/someValue
                element.forEachAttribute { attr ->
                    val value = attr.nodeValue
                    if (value.startsWith('?') && !value.contains('/')) {
                        val expanded = expandThemeAttrReference(value)
                        if (expanded != value) {
                            logger.fine { "Expanding theme attr reference '${attr.nodeName}'='$value' to '$expanded'" }
                            attr.nodeValue = expanded
                        }
                    }
                }
            }
        }
        return createdIds
    }

    private fun expandThemeAttrReference(value: String): String {
        // value starts with '?' and has no '/'
        val afterQuestion = value.substring(1)
        val colonIndex = afterQuestion.indexOf(':')
        return if (colonIndex >= 0) {
            // Has package prefix, e.g. ?android:someValue -> ?android:attr/someValue
            val packagePrefix = afterQuestion.substring(0, colonIndex)
            val attrName = afterQuestion.substring(colonIndex + 1)
            "?$packagePrefix:attr/$attrName"
        } else {
            // No package prefix, e.g. ?someValue -> ?attr/someValue
            "?attr/$afterQuestion"
        }
    }

    /**
     * The resources a file declares, in the order they appear, and whether it has an id declaration
     * or a shorthand theme attribute reference that [processIdAndAttrDeclarations] rewrites.
     */
    private inner class Declarations(
        val publicIds: List<Pair<String, String?>>,
        val needsRewrite: Boolean,
    ) {
        fun createPublicIds() = publicIds.forEach { (type, name) -> publicIdManager.createPublicId(type, name) }
    }

    private fun createPublicIdsFromValuesXml(file: File) = readDeclarations(file).createPublicIds()

    /**
     * Reads [file] in one streaming pass. Most changed files are values files that need no rewrite,
     * and parsing them as a document and writing them back is much slower. The parser resolves
     * character references, so a reference that spells an id declaration is found too.
     */
    private fun readDeclarations(file: File): Declarations {
        val publicIds = mutableListOf<Pair<String, String?>>()
        var needsRewrite = false

        file.parseXml { parser ->
            var eventType = parser.eventType
            var depth = 0
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (!needsRewrite) needsRewrite = parser.hasIdOrThemeAttrDeclaration()

                        val tagName = parser.name
                        if (depth >= 1) {
                            val idName = parser.getAttributeValue(null, "name")

                            val resolvedTagName = if (tagName == "item") {
                                parser.getAttributeValue(null, "type") ?: tagName
                            } else {
                                tagName
                            }
                            val publicTagName = resourceToTagOverrideMapping[resolvedTagName] ?: resolvedTagName
                            publicIds += publicTagName to idName
                        }
                        depth += 1
                    }
                    XmlPullParser.END_TAG -> {
                        depth -= 1
                    }
                }
                eventType = parser.next()
            }
        }

        return Declarations(publicIds, needsRewrite)
    }

    /**
     * If [file] starts with a UTF-16 byte order mark, or with a null byte, which UTF-16 text without one has.
     */
    private fun File.isUtf16(): Boolean {
        val head = ByteArray(2)
        val read = inputStream().use { it.read(head) }
        if (read < 2) return false
        val first = head[0].toInt() and 0xFF
        val second = head[1].toInt() and 0xFF
        return (first == 0xFE && second == 0xFF) || (first == 0xFF && second == 0xFE) || first == 0 || second == 0
    }

    /**
     * If the current tag has what [processIdAndAttrDeclarations] rewrites: an `android:id` that
     * declares an id, or an attribute value that is a shorthand theme attribute reference.
     */
    private fun XmlPullParser.hasIdOrThemeAttrDeclaration(): Boolean {
        for (i in 0 until attributeCount) {
            val value = getAttributeValue(i)
            if (value.startsWith('?') && !value.contains('/')) return true

            val prefix = getAttributePrefix(i)
            val qualifiedName = if (prefix == null) getAttributeName(i) else "$prefix:${getAttributeName(i)}"
            if (qualifiedName == "android:id" && value.startsWith("@+id/")) return true
        }
        return false
    }
}
