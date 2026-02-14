/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.util.Document
import org.w3c.dom.Element
import java.io.File
import java.io.FileNotFoundException
import java.util.logging.Logger
import kotlin.collections.set

class ResourceIdProcessor(
    val get: (path: String) -> File,
    val document: (path: String) -> Document,
    val packageName: String
) {
    private val logger = Logger.getLogger(ResourcePatchContext::class.java.name)

    fun process() {
        document("res/values/public.xml").use { publicDoc ->
            val publicNode = publicDoc.getElementsByTagName("resources").item(0) as Element
            publicNode.setAttribute("package", packageName)

            val resourceIds = mutableMapOf<String, Int>()
            val definedIds = mutableSetOf<String>()

            fun createPublicId(type: String, name: String) {
                if (definedIds.contains("@$type/$name")) {
                    return
                }

                logger.fine("Adding @$type/$name to public.xml")
                val resourceId = resourceIds[type]!! + 1
                resourceIds[type] = resourceId
                val item = publicDoc.createElement("public")
                item.setAttribute("id", "0x${resourceId.toString(16)}")
                item.setAttribute("type", type)
                item.setAttribute("name", name)
                publicNode.appendChild(item)
                definedIds.add("@$type/$name")
            }

            val resDirectories = get("res").listFiles { file -> file.isDirectory }

            publicDoc.getElementsByTagName("public").forEachElement { element ->
                val idString = element.getAttribute("id")
                val typeString = element.getAttribute("type")
                val nameString = element.getAttribute("name")
                val id = idString.substring(2).toInt(16)
                if (id > resourceIds.getOrElse(typeString, { 0 })) {
                    resourceIds[typeString] = id
                }
                // Need to add type because it is possible to have multiple resources with the same name but different types.
                definedIds.add("@$typeString/$nameString")
            }

            // Find all new ID declarations in layout/menu files so we can create a corresponding entry in ids.xml
            // They will get added to public.xml later
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

            resourceTypes.forEach { (resourceType, tagInfo) ->
                val xmlTagName = tagInfo.first
                val publicTagName = tagInfo.second

                valuesDirectories.forEach { dir ->
                    try {
                        document("res/${dir.name}/$resourceType.xml").use { doc ->
                            doc.getElementsByTagName(xmlTagName).forEachElement {
                                createPublicId(publicTagName, it.getAttribute("name"))
                            }
                        }
                    } catch (_: FileNotFoundException) {
                        // don't need to process
                    }
                }
            }

            fileResourceTypes.forEach { type ->
                val directories = resDirectories.filter { it.name.startsWith(type) }
                directories.forEach { dir ->
                    dir.listFiles { file -> file.isFile }
                        .map{ file -> file.name.split(".").first() }
                        .forEach { name ->
                            createPublicId(type, name)
                        }
                }
            }
        }
    }
}