/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.PublicXmlManager
import app.morphe.patcher.resource.fileResourceTypes
import app.morphe.patcher.resource.parseXml
import app.morphe.patcher.resource.resourceToTagOverrideMapping
import app.morphe.patcher.util.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.*
import java.util.logging.Logger

internal class ResourceIdProcessor(
    internal val get: (path: String) -> File,
    internal val publicIdManager: PublicXmlManager,
    internal val modifiedResources: Set<File>,
    internal val addedResources: Set<File>,
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

            (modifiedResources + addedResources)
                .filter { it.exists() && it.extension == "xml" }
                .forEach {
                    processNewIdDeclarations(it, idNode)
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

    private fun processNewIdDeclarations(file: File, idNode: Node): Set<String> {
        val createdIds = mutableSetOf<String>()
        Document(file).use { doc ->
            iterativeTraverse(doc.documentElement) { element ->
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
            }
        }
        return createdIds
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

    /**
     * Iterative traversal of all Element nodes in the subtree starting at [root].
     * Preserves original in-order behavior of inOrderTraverse.
     */
    private fun iterativeTraverse(root: Node, action: (Element) -> Unit) {
        val stack = ArrayDeque<Element>()
        if (root is Element) stack.add(root)

        while (stack.isNotEmpty()) {
            val elem = stack.removeLast()
            action(elem)

            // Add children to stack in reverse order to maintain order
            val children = elem.childNodes
            for (i in children.length - 1 downTo 0) {
                val child = children.item(i)
                if (child is Element) stack.add(child)
            }
        }
    }
}