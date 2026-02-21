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
import app.morphe.patcher.resource.resourceToTagOverrideMapping
import app.morphe.patcher.util.Document
import org.w3c.dom.Node
import org.xml.sax.SAXParseException
import java.io.File
import java.io.FileNotFoundException
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

        // TODO: Remove this workaround once we fix Piko patches.
        val pikoMap = mapOf(
            "piko_strings.xml" to "strings.xml",
            "piko_arrays.xml" to "arrays.xml",
            "piko_app_icon_colors.xml" to "colors.xml",
            "piko_app_icon_strings.xml" to "strings.xml",
        )

        valuesDirectories.forEach { dir ->
            dir.listFiles { file -> file.isFile }
                ?.filter { it.extension == "xml" && it.name != "public.xml" }
                ?.forEach { file ->
                    try {
                        Document(file).use { doc ->
                            val resourcesNode = doc.getElementsByTagName("resources").item(0)
                                ?: throw IllegalStateException("ids.xml is missing the <resources> root element.")

                            resourcesNode.childNodes.forEachElement {
                                val publicTagName = resourceToTagOverrideMapping[it.tagName] ?: it.tagName
                                publicIdManager.createPublicId(publicTagName, it.getAttribute("name"))
                            }

                            if (file.name in pikoMap) {
                                val originalFileName = pikoMap[file.name] ?: return@forEach
                                val originalFile = File(file.parentFile, originalFileName)
                                if (!originalFile.exists()) {
                                    throw FileNotFoundException("Expected to find $originalFileName for ${file.name} but it does not exist.")
                                }

                                Document(originalFile).use { originalDoc ->
                                    val originalResourcesNode = originalDoc.getElementsByTagName("resources").item(0)
                                        ?: throw IllegalStateException("$originalFileName is missing the <resources> root element.")

                                    resourcesNode.childNodes.forEachElement {
                                        originalDoc.adoptNode(it.cloneNode(true)).also { importedNode ->
                                            originalResourcesNode.appendChild(importedNode)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: FileNotFoundException) {
                        // don't need to process
                    } catch (e: SAXParseException) {
                        logger.warning("Failed to parse res/${dir.name}/${file.name}: ${e.message}")
                    }

                    if (file.name in pikoMap) {
                        file.delete()
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