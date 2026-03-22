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
) {
    private val logger = Logger.getLogger(ResourceIdProcessor::class.java.name)

    fun process() {
        logger.info("Generating new resource IDs")

        val resDirectories =
            get("res").listFiles { file -> file.isDirectory } ?: throw PatchException("Resource directory not found")

        // Find all new ID declarations in XML files so we can create a corresponding entry in ids.xml
        // They will get added to public.xml later
        Document(get("res/values/ids.xml")).use { idDoc ->
            val idNode = idDoc.getElementsByTagName("resources").item(0)
                ?: throw IllegalStateException("ids.xml is missing the <resources> root element.")

            modifiedResResources
                .filter { it.exists() && it.extension == "xml" }
                .forEach {
                    processIdAndAttrDeclarations(it, idNode)
                    // Update publicIdManager from values XML files (later, including the newly modified ids.xml)
                    createPublicIdsFromValuesXml(it)
                }
        }

        createPublicIdsFromValuesXml(get("res/values/ids.xml"))

        // Step 3: Ensure all other resources have a public ID
        // TODO: Only enumerate through files that have been modified by patches.
        for (type in fileResourceTypes) {
            val directories = resDirectories.filter { it.name.startsWith(type) }
            for (dir in directories) {
                val files = dir.listFiles { file -> file.isFile } ?: continue
                for (file in files) {
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

    private fun createPublicIdsFromValuesXml(file: File) {
        file.parseXml { parser ->
            var eventType = parser.eventType
            var depth = 0
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name
                        if (depth >= 1) {
                            val idName = parser.getAttributeValue(null, "name")

                            val resolvedTagName = if (tagName == "item") {
                                parser.getAttributeValue(null, "type") ?: tagName
                            } else {
                                tagName
                            }
                            val publicTagName = resourceToTagOverrideMapping[resolvedTagName] ?: resolvedTagName
                            publicIdManager.createPublicId(publicTagName, idName)
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
    }
}