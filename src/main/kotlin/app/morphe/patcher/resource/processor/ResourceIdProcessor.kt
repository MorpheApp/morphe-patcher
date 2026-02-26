/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.PublicXmlManager
import app.morphe.patcher.resource.fileResourceTypes
import app.morphe.patcher.resource.resourceToTagOverrideMapping
import app.morphe.patcher.util.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXParseException
import java.io.File
import java.io.FileNotFoundException
import java.util.ArrayDeque
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
        val nonTrackedFiles = mutableSetOf<File>()

        // Find all new ID declarations in layout/menu files so we can create a corresponding entry in ids.xml
        // They will get added to public.xml later
        // TODO: Only handle this for newly added files (this is a breaking change).
        Document(get("res/values/ids.xml")).use { idDoc ->
            val idNode = idDoc.getElementsByTagName("resources").item(0)
                ?: throw IllegalStateException("ids.xml is missing the <resources> root element.")

            (modifiedResources + addedResources)
                .filter { it.exists() && it.extension == "xml" }
                .forEach { processNewIdDeclarations(it, idNode) }

            // TODO: Check if we need to look through any other XML files for new ID declarations.
            resDirectories
                .filter { it.name.startsWith("layout") || it.name.startsWith("menu") }
                .forEach { dir ->
                    val files = dir.listFiles { file -> file.isFile } ?: return@forEach
                    for (file in files) {
                        if (file in modifiedResources || file in addedResources) continue
                        val nonTrackedIds = processNewIdDeclarations(file, idNode)
                        if (nonTrackedIds.isNotEmpty()) nonTrackedFiles += file
                    }
                }
        }

        // Step 2: Update publicIdManager from values XML files
        val valuesDirectories = resDirectories.filter { it.name.startsWith("values") }
        for (dir in valuesDirectories) {
            val files = dir.listFiles { file -> file.isFile } ?: continue
            for (file in files) {
                if (file.extension != "xml" || file.name == "public.xml") continue
                try {
                    Document(file).use { doc ->
                        val resourcesNode = doc.getElementsByTagName("resources").item(0)
                            ?: throw IllegalStateException("<resources> root missing in ${file.name}")
                        // Use stack-based iterative traversal instead of recursive forEachElement
                        iterativeTraverse(resourcesNode) { element ->
                            val publicTagName =
                                resourceToTagOverrideMapping[element.nodeName] ?: element.nodeName
                            publicIdManager.createPublicId(publicTagName, element.getAttribute("name"))
                        }
                    }
                } catch (_: FileNotFoundException) {
                    // ignore
                } catch (e: SAXParseException) {
                    logger.warning("Failed to parse res/${dir.name}/${file.name}: ${e.message}")
                }
            }
        }

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

        if (nonTrackedFiles.isNotEmpty()) {
            val fileNames = nonTrackedFiles.map { it.name }
            logger.fine("Found ${nonTrackedFiles.size} modified files that were not tracked: $fileNames")
        }
    }

    private fun processNewIdDeclarations(file: File, idNode: Node): Set<String> {
        val createdIds = mutableSetOf<String>()
        Document(file).use { doc ->
            iterativeTraverse(doc.documentElement) { element ->
                val idString = element.getAttribute("android:id")
                if (idString.startsWith("@+id/")) {
                    logger.fine("Adding $idString to ids.xml")
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