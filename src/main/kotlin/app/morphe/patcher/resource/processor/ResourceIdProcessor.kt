/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.PublicXmlManager
import app.morphe.patcher.resource.fileResourceTypes
import app.morphe.patcher.resource.forEachElement
import app.morphe.patcher.resource.inOrderTraverse
import app.morphe.patcher.resource.resourceTypes
import app.morphe.patcher.util.Document
import org.w3c.dom.Node
import java.io.File
import java.io.FileNotFoundException
import java.util.logging.Logger

class ResourceIdProcessor(
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
                .forEach {
                    processNewIdDeclarations(it, idNode)
                }

            // TODO: Check if we need to look through any other XML files for new ID declarations.
            resDirectories
                .filter { it.name.startsWith("layout") || it.name.startsWith("menu") }
                .forEach { dir ->
                    dir.listFiles { file -> file.isFile }
                        ?.filter { !modifiedResources.contains(it) && !addedResources.contains(it) }
                        ?.forEach { file ->
                            val nonTrackedIds = processNewIdDeclarations(file, idNode)
                            if (nonTrackedIds.isNotEmpty()) {
                                nonTrackedFiles += file
                            }
                        }
                }
        }

        val valuesDirectories = resDirectories.filter { it.name.startsWith("values") }

        // TODO: Only enumerate through files that have been modified by patches.
        resourceTypes.forEach { (resourceType, tagInfo) ->
            val xmlTagName = tagInfo.first
            val publicTagName = tagInfo.second

            valuesDirectories.forEach { dir ->
                try {
                    Document(get("res/${dir.name}/$resourceType.xml")).use { doc ->
                        doc.getElementsByTagName(xmlTagName).forEachElement {
                            publicIdManager.createPublicId(publicTagName, it.getAttribute("name"))
                        }
                    }
                } catch (_: FileNotFoundException) {
                    // don't need to process
                }
            }
        }

        // TODO: Only enumerate through files that have been modified by patches.
        fileResourceTypes.forEach { type ->
            val directories = resDirectories.filter { it.name.startsWith(type) }
            directories.forEach { dir ->
                dir.listFiles { file -> file.isFile }
                    // TODO: This generates superfluous IDs for existing files like 9patch files.
                    ?.map { file -> file.nameWithoutExtension }
                    ?.forEach { name ->
                        publicIdManager.createPublicId(type, name)
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
            doc.inOrderTraverse {
                val idString = it.getAttribute("android:id")
                if (idString.startsWith("@+id/")) {
                    logger.fine("Adding $idString to ids.xml")
                    val idName = idString.substring(5)
                    val item = idNode.ownerDocument.createElement("id")
                    item.setAttribute("name", idName)
                    idNode.appendChild(item)

                    it.setAttribute("android:id", "@id/$idName")

                    createdIds.add("@id/$idName")
                }
            }
        }

        return createdIds
    }
}