/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.fileResourceTypes
import app.morphe.patcher.resource.parseXml
import app.morphe.patcher.resource.postOrderTraverse
import app.morphe.patcher.resource.utf8Writer
import app.morphe.patcher.util.Document
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.ArrayDeque
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal class AaptMacroProcessor(
    internal val get: (path: String) -> File,
    internal val modifiedResources: Set<File>,
    internal val addedResources: MutableSet<File>,
) {
    private val logger = Logger.getLogger(AaptMacroProcessor::class.java.name)

    private val aaptNameToResourceType = mapOf(
        "android:animation" to "drawable",
        "android:drawable" to "drawable",
        "android:fillColor" to "drawable"
    )

    private val dbFactory = DocumentBuilderFactory.newInstance()
    private val docBuilder = dbFactory.newDocumentBuilder()
    private val transformerFactory = TransformerFactory.newInstance()
    private val transformer = transformerFactory.newTransformer()

    fun process() {
        logger.info("Processing aapt macros")

        // Additionally, handle the process of creating new IDs here so we don't have to read the same files again.
        // (This will require refactoring of the code that handles public.xml id generation.)
        val newlyCreatedFiles = mutableSetOf<File>()
        (modifiedResources + addedResources)
            .filter { it.exists() && it.extension == "xml" }
            .forEach { newlyCreatedFiles += processDocument(it) }
    }

    private fun processDocument(file: File): Set<File> {
        val newlyCreatedFiles = mutableSetOf<File>()

        // First pass: check if the file contains aapt namespace at all (quick scan).
        var hasAaptNamespace = false
        file.parseXml { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.depth == 1) {
                    // Check if the root element declares the aapt namespace
                    val nsCount = parser.getNamespaceCount(1)
                    for (i in 0 until nsCount) {
                        if (parser.getNamespaceUri(i) == "http://schemas.android.com/aapt") {
                            hasAaptNamespace = true
                            break
                        }
                    }
                    break
                }
                event = parser.next()
            }
        }

        if (!hasAaptNamespace) return emptySet()

        var aaptCounter = 0

        Document(file).use { doc ->
            val topNodes = doc.childNodes
            for (i in 0 until topNodes.length) {
                val topLevelElem = topNodes.item(i) as? Element ?: continue
                topLevelElem.removeAttribute("xmlns:aapt")

                topLevelElem.postOrderTraverse { element ->
                    if (element.nodeName != "aapt:attr") return@postOrderTraverse

                    val shadowedName = "$${file.nameWithoutExtension}__$aaptCounter"
                    aaptCounter++

                    val parentElement = element.parentNode as Element
                    val parentAttribute = element.getAttribute("name")
                    val resourceType = aaptNameToResourceType[parentAttribute]
                        ?: throw PatchException("Unhandled XML attribute: $parentAttribute")
                    parentElement.setAttribute(parentAttribute, "@$resourceType/$shadowedName")

                    // Find first child element without allocating a list
                    val childNodes = element.childNodes
                    var sourceElement: Element? = null
                    for (j in 0 until childNodes.length) {
                        val child = childNodes.item(j) as? Element
                        if (child != null) {
                            sourceElement = child
                            break
                        }
                    }
                    if (sourceElement == null)
                        throw PatchException("aapt:attr element has no child element in ${file.name}")

                    sourceElement.setAttribute("xmlns:android", "http://schemas.android.com/apk/res/android")

                    val newElementFile = get("res/$resourceType/$shadowedName.xml")
                    extractElementToNewDocument(sourceElement, newElementFile)
                    newlyCreatedFiles.add(newElementFile)

                    // Remove the now-empty aapt:attr element from the source document
                    parentElement.removeChild(element)
                }
            }
        }

        return newlyCreatedFiles
    }

    private fun extractElementToNewDocument(element: Element, file: File) {
        val copiedDocument = docBuilder.newDocument()
        val copiedRoot = copiedDocument.importNode(element, true)
        copiedDocument.appendChild(copiedRoot)

        writeDocumentToFile(copiedDocument, file)
        element.parentNode.removeChild(element)
    }

    private fun writeDocumentToFile(doc: org.w3c.dom.Document, file: File) {
        file.utf8Writer().use { writer ->
            transformer.transform(DOMSource(doc), StreamResult(writer))
        }
        addedResources.add(file)
    }
}