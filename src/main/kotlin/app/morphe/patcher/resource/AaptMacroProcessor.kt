/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.Document
import org.w3c.dom.Element
import java.io.File
import java.io.FileWriter
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class AaptMacroProcessor(
    internal val get: (path: String) -> File,
    internal val modifiedResources: Set<File>,
    internal val addedResources: MutableSet<File>,
) {
    private val logger = Logger.getLogger(AaptMacroProcessor::class.java.name)

    // TODO: Make a better way of determining this
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
            .forEach {
            newlyCreatedFiles += processDocument(it)
        }

        val nonTrackedFiles = mutableSetOf<File>()
        fileResourceTypes
            .map { get("res/$it") }
            .filter { it.exists() && it.isDirectory }
            .forEach { dir ->
                dir.listFiles { file -> file.isFile && file.extension == "xml" && !file.startsWith("$") }
                    .filter { !newlyCreatedFiles.contains(it) && !modifiedResources.contains(it) && !addedResources.contains(it) }
                    .forEach { file ->
                         val res = processDocument(file)
                         if (res.isNotEmpty()) {
                             nonTrackedFiles += file
                         }
                    }
            }

        if (nonTrackedFiles.isNotEmpty()) {
            val fileNames = nonTrackedFiles.map { it.name }
            logger.fine("Found ${nonTrackedFiles.size} modified files that were not tracked: $fileNames")
        }
    }

    private fun processDocument(file: File): Set<File> {
        val newlyCreatedFiles = mutableSetOf<File>()

        var aaptCounter = 0
        Document(file).use { doc ->
            doc.childNodes
                .mapNotNull { it as? Element }
                .filter { it.hasAttribute("xmlns:aapt") }
                .forEach { topLevelElem ->
                    topLevelElem.postOrderTraverse({ element ->
                        if (element.nodeName != "aapt:attr") {
                            return@postOrderTraverse
                        }

                        val shadowedName = "$${file.nameWithoutExtension}__$aaptCounter"
                        aaptCounter++

                        val parentElement = element.parentNode as Element
                        val parentAttribute = element.getAttribute("name")

                        val resourceType = aaptNameToResourceType[parentAttribute] ?: throw PatchException("Unhandled XML attribute: $parentAttribute")
                        parentElement.setAttribute(parentAttribute, "@$resourceType/$shadowedName")

                        val sourceElement = element.childNodes.first { it is Element } as Element
                        sourceElement.setAttribute(
                            "xmlns:android",
                            "http://schemas.android.com/apk/res/android"
                        )

                        val newElementFilename = "res/$resourceType/$shadowedName.xml"
                        val newElementFile = get(newElementFilename)
                        extractElementToNewDocument(sourceElement, newElementFile)
                        newlyCreatedFiles.add(newElementFile)
                    })
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
        val source = DOMSource(doc)
        val writer = FileWriter(file)
        val result = StreamResult(writer)
        transformer.transform(source, result)
        addedResources.add(file)
    }
}