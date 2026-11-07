/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.parseXml
import app.morphe.patcher.resource.utf8Writer
import app.morphe.patcher.resource.xml.ANDROID_NAMESPACE
import app.morphe.patcher.resource.xml.ElementBase
import app.morphe.patcher.resource.xml.TextXmlDocument
import app.morphe.patcher.resource.xml.writeDocument
import app.morphe.patcher.xml.XmlElement
import com.reandroid.apk.ApkUtil
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.logging.Logger

internal class AaptMacroProcessor(
    internal val get: (path: String) -> File,
    internal val modifiedResResources: MutableSet<File>,
) {
    private val logger = Logger.getLogger(AaptMacroProcessor::class.java.name)

    private val aaptNameToResourceType = mapOf(
        "android:animation" to "drawable",
        "android:drawable" to "drawable",
        "android:fillColor" to "drawable"
    )

    /**
     * @param onProgress Called with how many of the files that can hold macros are processed.
     */
    fun process(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        logger.info("Processing aapt macros")

        // Additionally, handle the process of creating new IDs here so we don't have to read the same files again.
        // (This will require refactoring of the code that handles public.xml id generation.)
        val newlyCreatedFiles = mutableSetOf<File>()
        // aapt2 inlines aapt:attr only in files compiled as file resources, never in values files,
        // which are most of what patches modify, so those are not opened.
        val files = modifiedResResources.filter { it.extension == "xml" && !it.isValuesFile() && it.exists() }
        files.forEachIndexed { index, file ->
            newlyCreatedFiles += processDocument(file)
            onProgress(index + 1, files.size)
        }
    }

    private fun File.isValuesFile() = parentFile?.let { ApkUtil.isValuesDirectoryName(it.name, true) } == true

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

        val document = file.inputStream().buffered().use { TextXmlDocument.read(it) }
        document.use {
            document.root.removeAttribute("xmlns:aapt")

            // Nested macros are extracted first, so that the element they are in no longer has them.
            document.root.postOrder().filter { it.tag == "aapt:attr" }.forEach { element ->
                val shadowedName = "$${file.nameWithoutExtension}__$aaptCounter"
                aaptCounter++

                val parentElement = element.parent!!
                val parentAttribute = element.attribute("name").orEmpty()
                val resourceType = aaptNameToResourceType[parentAttribute]
                    ?: throw PatchException("Unhandled XML attribute: $parentAttribute")
                parentElement.setAttribute(parentAttribute, "@$resourceType/$shadowedName")

                val sourceElement = element.children.firstOrNull()
                    ?: throw PatchException("aapt:attr element has no child element in ${file.name}")

                val newElementFile = get("res/$resourceType/$shadowedName.xml")
                writeElementToFile(sourceElement, newElementFile)
                newlyCreatedFiles.add(newElementFile)

                // Removes the macro, and the element it holds, from the source document.
                element.remove()
            }
            file.utf8Writer().use { document.root.writeDocument(it) }
        }

        return newlyCreatedFiles
    }

    /**
     * Writes a copy of [element] as the root of [file]. The copy declares the namespaces it uses.
     */
    private fun writeElementToFile(element: XmlElement, file: File) {
        TextXmlDocument.detached(element.tag).use { copy ->
            copy.root.setAttribute("xmlns:android", ANDROID_NAMESPACE)
            copy.root.copyContentFrom(element as ElementBase)
            file.utf8Writer().use { copy.root.writeDocument(it) }
        }
        modifiedResResources.add(file)
    }

    /**
     * This element and every element below it, each after the elements below it.
     */
    private fun XmlElement.postOrder(): List<XmlElement> {
        val elements = mutableListOf<XmlElement>()
        fun visit(element: XmlElement) {
            element.children.forEach(::visit)
            elements += element
        }
        visit(this)
        return elements
    }
}
