/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.fileResourceTypes
import app.morphe.patcher.resource.parseXml
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

        // TODO: Only handle newly added resource files here. (This is a breaking change.)
        // Additionally, handle the process of creating new IDs here so we don't have to read the same files again.
        // (This will require refactoring of the code that handles public.xml id generation.)
        val newlyCreatedFiles = mutableSetOf<File>()
        (modifiedResources + addedResources)
            .filter { it.exists() && it.extension == "xml" }
            .forEach { newlyCreatedFiles += processDocument(it) }

        val nonTrackedFiles = mutableSetOf<File>()
        fileResourceTypes
            .map { get("res/$it") }
            .filter { it.exists() && it.isDirectory }
            .forEach { dir ->
                dir.listFiles { file -> file.isFile && file.extension == "xml" && !file.name.startsWith("$") }
                    ?.forEach { file ->
                        if (file in newlyCreatedFiles || file in modifiedResources || file in addedResources) return@forEach
                        val res = processDocument(file)
                        if (res.isNotEmpty()) nonTrackedFiles += file
                    }
            }

        if (nonTrackedFiles.isNotEmpty()) {
            val fileNames = nonTrackedFiles.map { it.name }
            logger.fine { "Found ${nonTrackedFiles.size} modified files that were not tracked: $fileNames" }
        }
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

                // Replace recursive postOrderTraverse with iterative stack-based version
                iterativePostOrder(topLevelElem) { element ->
                    if (element.nodeName != "aapt:attr") return@iterativePostOrder

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

    /**
     * Iterative post-order traversal for Element nodes.
     * Preserves original postOrderTraverse behavior without recursion.
     */
    private fun iterativePostOrder(root: Element, action: (Element) -> Unit) {
        data class StackNode(val element: Element, var visited: Boolean = false)

        val stack = ArrayDeque<StackNode>()
        stack.add(StackNode(root))

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.visited) {
                action(node.element)
                continue
            }

            node.visited = true
            stack.add(node) // Add back to process after children

            // Add children to stack
            val children = node.element.childNodes
            for (i in children.length - 1 downTo 0) {
                val child = children.item(i)
                if (child is Element) stack.add(StackNode(child))
            }
        }
    }
}