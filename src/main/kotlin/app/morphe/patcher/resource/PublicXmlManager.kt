/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.util.logging.Logger

class PublicXmlManager(
    private val publicDocPath: File
) : Closeable {
    private val logger = Logger.getLogger(this::class.java.name)
    private val resourceIds = mutableMapOf<String, Int>()
    private val definedIds = mutableMapOf<Pair<String, String>, Int>()
    private lateinit var packageName: String
    private lateinit var packageId: String

    init {
        if (!publicDocPath.exists()) {
            throw IllegalArgumentException("File does not exist at path: ${publicDocPath.absolutePath}")
        }
        readPublicXml()
    }

    private fun readPublicXml() {
        publicDocPath.parseXml { parser ->
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "resources") {
                            val attributes = parser.getAttributesAsMap()
                            packageName = attributes["package"] ?: throw IllegalStateException("Missing 'package' attribute in <resources> element.")
                            packageId = attributes["id"] ?: throw IllegalStateException("Missing 'id' attribute in <resources> element.")
                        }
                        else if (parser.name == "public") {
                            val attributes = parser.getAttributesAsMap()
                            val idString = attributes["id"] ?: throw IllegalStateException("Missing 'id' attribute in <public> element.")
                            val typeString = attributes["type"] ?: throw IllegalStateException("Missing 'type' attribute in <public> element.")
                            val nameString = attributes["name"] ?: throw IllegalStateException("Missing 'name' attribute in <public> element.")

                            val id = try {
                                idString.substring(2).toInt(16)
                            } catch (_: NumberFormatException) {
                                throw IllegalStateException("Malformed id in <public> element: '$idString'" +
                                        " (expected format like '0x1234')."
                                )
                            }

                            if (id > resourceIds.getOrElse(typeString) { 0 }) {
                                resourceIds[typeString] = id
                            }
                            // Need to add type because it is possible to have multiple resources with the same name but different types.
                            definedIds[Pair(typeString, nameString)] = id
                        }
                    }
                }
                eventType = parser.next()
            }
        }
    }

    fun idExists(type: String, name: String): Boolean {
        return definedIds.contains(Pair(type, name))
    }

    fun getHighestResourceIds(): Map<String, Int> {
        return resourceIds
    }

    fun getDefinedIds(): Map<Pair<String, String>, Int> {
        return definedIds
    }

    fun createPublicId(type: String, name: String?) {
        if (name == null || idExists(type, name)) {
            return
        }

        logger.fine { "Adding @$type/$name to public.xml" }

        val resourceId = resourceIds.getOrElse(type) { 0 } + 1
        resourceIds[type] = resourceId
        definedIds[Pair(type, name)] = resourceId
    }

    internal fun changePackageName(packageName: String) {
        this.packageName = packageName
    }

    internal fun changePackageId(packageId: Int) {
        this.packageId = "0x${packageId.toString(16)}"
    }

    override fun close() {
        val tempFile = File(publicDocPath.parentFile, publicDocPath.name + ".tmp")
        tempFile.writeXml { serializer ->
            serializer.startDocument("UTF-8", true)

            // Write <resources> start tag with package and id attributes
            serializer.startTag(null, "resources")
            serializer.attribute(null, "package", packageName)
            serializer.attribute(null, "id", packageId)

            // Write <public> tags for each resource ID
            definedIds.map {
                val (type, name) = it.key
                val id = it.value
                Triple(type, name, id)
            }.sortedBy { it.third }.forEach { (type, name, id) ->
                serializer.startTag(null, "public")
                serializer.attribute(null, "id", "0x${id.toString(16)}")
                serializer.attribute(null, "type", type)
                serializer.attribute(null, "name", name)
                serializer.endTag(null, "public")
            }

            // Write </resources> end tag
            serializer.endTag(null, "resources")
            serializer.endDocument()
        }

        // Replace original file
        publicDocPath.delete()
        tempFile.renameTo(publicDocPath)
    }
}