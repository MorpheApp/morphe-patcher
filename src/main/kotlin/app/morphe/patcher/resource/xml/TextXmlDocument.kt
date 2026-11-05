/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.xml

import app.morphe.patcher.resource.utf8Writer
import java.io.File
import java.io.InputStream

/**
 * XML read from text: a file of the working directory, which is written back when the document is
 * closed if it changed, or a stream, which is never written.
 *
 * @param file The file the document was read from, or null if it was read from a stream.
 * @param onClosed Called once the document was closed and written.
 */
internal class TextXmlDocument private constructor(
    internal val file: File?,
    private val onClosed: (TextXmlDocument) -> Unit,
) : TreeDocument() {
    override lateinit var root: TreeElement
        private set

    override fun onClose() {
        if (file != null && modified) file.utf8Writer().use { root.writeDocument(it) }
        onClosed(this)
    }

    internal companion object {
        fun read(file: File, onClosed: (TextXmlDocument) -> Unit = {}) = TextXmlDocument(file, onClosed).apply {
            file.inputStream().buffered().use { root = parseTree(it) }
        }

        fun read(input: InputStream) = TextXmlDocument(null) {}.apply { root = parseTree(input) }

        /**
         * A new document that is not read from anything and never written, with a root element of [tag].
         */
        fun detached(tag: String) = TextXmlDocument(null) {}.apply { root = createElement(tag) }
    }
}
