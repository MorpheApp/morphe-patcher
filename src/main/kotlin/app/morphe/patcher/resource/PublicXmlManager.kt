/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import app.morphe.patcher.util.Document
import org.w3c.dom.Element
import java.io.Closeable
import java.io.File
import java.util.logging.Logger

class PublicXmlManager(
    internal val publicDocPath: File
) : Closeable {
    private val publicDoc: Document
    init {
        if (!publicDocPath.exists()) {
            throw IllegalArgumentException("File does not exist at path: ${publicDocPath.absolutePath}")
        }

        publicDoc = Document(publicDocPath)
    }

    private val logger = Logger.getLogger(PublicXmlManager::class.java.name)
    private val publicNode: Element = publicDoc.getElementsByTagName("resources").item(0)?.let { node ->
        node as? Element ?: throw IllegalStateException("Root <resources> element in public.xml is not an Element.")
    } ?: throw IllegalStateException("Missing <resources> root element in public.xml.")

    private val resourceIds = mutableMapOf<String, Int>()
    private val definedIds = readExistingIds()

    internal fun readExistingIds(): MutableSet<Pair<String, String>> {
        val ids = mutableSetOf<Pair<String, String>>()

        publicDoc.getElementsByTagName("public").forEachElement { element ->
            val idString = element.getAttribute("id")
            val typeString = element.getAttribute("type")
            val nameString = element.getAttribute("name")

            if (!idString.startsWith("0x") || idString.length <= 2) {
                logger.warning("Skipping <public> element with malformed id attribute: '$idString' (expected format like '0x1234').")
                return@forEachElement
            }

            val id = try {
                idString.substring(2).toInt(16)
            } catch (e: NumberFormatException) {
                logger.warning("Skipping <public> element with non-hex id attribute: '$idString'.")
                return@forEachElement
            }
            if (id > resourceIds.getOrElse(typeString, { 0 })) {
                resourceIds[typeString] = id
            }
            // Need to add type because it is possible to have multiple resources with the same name but different types.
            ids.add(Pair(typeString, nameString))
        }

        return ids
    }

    fun idExists(type: String, name: String): Boolean {
        return definedIds.contains(Pair(type, name))
    }

    fun createPublicId(type: String, name: String) {
        if (idExists(type, name)) {
            return
        }

        logger.fine("Adding @$type/$name to public.xml")

        val resourceId = resourceIds.getOrElse(type) { 0 } + 1
        resourceIds[type] = resourceId
        val item = publicDoc.createElement("public")
        item.setAttribute("id", "0x${resourceId.toString(16)}")
        item.setAttribute("type", type)
        item.setAttribute("name", name)
        publicNode.appendChild(item)
        definedIds.add(Pair(type, name))
    }

    fun getPublicNode(): Element {
        return publicNode
    }

    override fun close() {
        publicDoc.close()
    }
}