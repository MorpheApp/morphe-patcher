/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import app.morphe.patcher.util.Document
import java.io.File
import java.io.FileNotFoundException
import java.util.logging.Logger

class ResourceIdProcessor(
    internal val get: (path: String) -> File,
    internal val document: (path: String) -> Document,
    internal val publicIdManager: PublicXmlManager,
) {
    private val logger = Logger.getLogger(ResourceIdProcessor::class.java.name)

    fun process() {
        logger.info("Generating new resource IDs")

        val resDirectories = get("res").listFiles { file -> file.isDirectory }

        // Find all new ID declarations in layout/menu files so we can create a corresponding entry in ids.xml
        // They will get added to public.xml later
        // TODO: Only handle this for newly added files (this is a breaking change).
        document("res/values/ids.xml").use { idDoc ->
            val idNode = idDoc.getElementsByTagName("resources").item(0)

            // TODO: Check if we need to look through any other XML files for new ID declarations.
            resDirectories.filter { it.name.startsWith("layout") || it.name.startsWith("menu") }.forEach { dir ->
                dir.listFiles { file -> file.isFile }.forEach { file ->
                    document("res/${dir.name}/${file.name}").use { doc ->
                        doc.inOrderTraverse {
                            val idString = it.getAttribute("android:id")
                            if (idString.startsWith("@+id/")) {
                                logger.fine("Adding $idString to ids.xml")
                                val idName = idString.substring(5)
                                val item = idDoc.createElement("id")
                                item.setAttribute("name", idName)
                                idNode.appendChild(item)
                                it.setAttribute("android:id", "@id/$idName")
                            }
                        }
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
                    document("res/${dir.name}/$resourceType.xml").use { doc ->
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
                    .map { file -> file.nameWithoutExtension }
                    .forEach { name ->
                        publicIdManager.createPublicId(type, name)
                    }
            }
        }
    }
}