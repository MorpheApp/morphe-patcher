package app.morphe.patcher.util

import app.morphe.patcher.resource.utf8Writer
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.DocumentType
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.ProcessingInstruction
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.Writer
import javax.xml.parsers.DocumentBuilderFactory

class Document private constructor(
    document: Document,
    private val file: File?,
) : Document by document, Closeable {
    init {
        normalize()
    }

    internal constructor(inputStream: InputStream) : this(parse(inputStream), null)

    internal constructor(file: File) : this(file.inputStream().buffered().use(::parse), file) {
        readerCount.merge(file, 1, Int::plus)
    }

    override fun close() {
        file?.let {
            if (readerCount[it]!! > 1) {
                throw IllegalStateException(
                    "Two or more instances are currently reading $it." +
                        "To be able to close this instance, no other instances may be reading $it at the same time.",
                )
            } else {
                readerCount.remove(it)
            }

            it.utf8Writer().use { writer -> XmlWriter(writer).writeDocument(this) }
        }
    }

    private companion object {
        private val readerCount = mutableMapOf<File, Int>()

        fun parse(inputStream: InputStream): Document =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(inputStream).apply {
                documentElement?.resolveAttributeNamespaces(emptyMap())
            }

        /**
         * Gives each prefixed attribute the namespace its prefix is declared with, as a namespace aware
         * parser would, so that the namespace methods such as [Element.setAttributeNS] replace a parsed
         * attribute instead of adding a second attribute with the same name.
         *
         * The parser is not namespace aware, because a namespace aware parser fails on a prefix that is
         * not declared, and on Android leaves the `xmlns` attributes out of the document.
         *
         * @param namespaces The namespace URI each prefix is declared with at the parent element.
         */
        private fun Element.resolveAttributeNamespaces(namespaces: Map<String, String>) {
            val attributeList = (0 until attributes.length).map { attributes.item(it) as Attr }

            var elementNamespaces = namespaces
            attributeList.filter { it.name.startsWith("xmlns:") }.takeIf { it.isNotEmpty() }?.let { declarations ->
                elementNamespaces = namespaces + declarations.map { it.name.substring("xmlns:".length) to it.value }
            }

            if (elementNamespaces.isNotEmpty()) {
                val namespaceURIs = attributeList.map { attribute ->
                    val prefix = attribute.name.substringBefore(':', "")
                    if (prefix == "xmlns") null else elementNamespaces[prefix]
                }

                // All attributes are added again in their order, which adding only the replaced ones would change.
                if (namespaceURIs.any { it != null }) {
                    attributeList.forEach(::removeAttributeNode)
                    attributeList.forEachIndexed { index, attribute ->
                        val namespaceURI = namespaceURIs[index]
                        if (namespaceURI == null) {
                            setAttributeNode(attribute)
                        } else {
                            setAttributeNodeNS(
                                ownerDocument.createAttributeNS(namespaceURI, attribute.name).also {
                                    it.value = attribute.value
                                },
                            )
                        }
                    }
                }
            }

            var child = firstChild
            while (child != null) {
                if (child is Element) child.resolveAttributeNamespaces(elementNamespaces)
                child = child.nextSibling
            }
        }
    }
}

/**
 * Writes a DOM document as UTF-8 XML.
 *
 * This replaces the platform transformer, which on Android checks every character against the output
 * encoding and takes a lock for every character it writes, and which escapes surrogate pairs to
 * invalid character references unless it writes UTF-16.
 *
 * Namespace declarations missing from the document are added where they are first used, such as for
 * nodes a patch adds in a namespace the document does not declare.
 */
internal class XmlWriter(private val writer: Writer) {
    /**
     * The namespace URI each prefix is bound to at the element being written.
     * The default namespace has the empty prefix.
     */
    private val boundNamespaces = mutableMapOf<String, String>()

    fun writeDocument(document: Document) {
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"")
        if (document.xmlStandalone) writer.write(" standalone=\"yes\"")
        writer.write("?>")
        var child = document.firstChild
        while (child != null) {
            writeNode(child)
            child = child.nextSibling
        }
    }

    private fun writeNode(node: Node) {
        when (node.nodeType) {
            Node.ELEMENT_NODE -> writeElement(node as Element)
            Node.TEXT_NODE -> writeEscaped(node.nodeValue, inAttribute = false)
            Node.CDATA_SECTION_NODE -> {
                // "]]>" cannot be inside a CDATA section, so it is split into two sections.
                writer.write("<![CDATA[")
                writer.write(node.nodeValue.replace("]]>", "]]]]><![CDATA[>"))
                writer.write("]]>")
            }

            Node.COMMENT_NODE -> {
                writer.write("<!--")
                writer.write(node.nodeValue)
                writer.write("-->")
            }

            Node.PROCESSING_INSTRUCTION_NODE -> {
                val instruction = node as ProcessingInstruction
                writer.write("<?")
                writer.write(instruction.target)
                if (!instruction.data.isNullOrEmpty()) {
                    writer.write(" ")
                    writer.write(instruction.data)
                }
                writer.write("?>")
            }

            Node.DOCUMENT_TYPE_NODE -> writeDocumentType(node as DocumentType)

            Node.ENTITY_REFERENCE_NODE -> {
                writer.write("&")
                writer.write(node.nodeName)
                writer.write(";")
            }

            else -> throw IllegalStateException("Cannot write XML node of type ${node.nodeType}")
        }
    }

    private fun writeElement(element: Element) {
        writer.write("<")
        writer.write(element.tagName)

        // The previous bindings of the prefixes this element binds, restored when the element ends.
        val previousBindings = mutableListOf<Pair<String, String?>>()
        fun bind(prefix: String, namespaceURI: String) {
            previousBindings += prefix to boundNamespaces.put(prefix, namespaceURI)
        }

        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index) as Attr
            if (attribute.name == "xmlns") {
                bind("", attribute.value)
            } else if (attribute.name.startsWith("xmlns:")) {
                bind(attribute.name.substring("xmlns:".length), attribute.value)
            }
        }

        fun declareIfUnbound(prefix: String?, namespaceURI: String?) {
            if (namespaceURI == null || prefix == "xml" || namespaceURI == XMLNS_NAMESPACE_URI) return
            val boundPrefix = prefix.orEmpty()
            if (boundNamespaces[boundPrefix] == namespaceURI) return

            bind(boundPrefix, namespaceURI)
            writer.write(if (boundPrefix.isEmpty()) " xmlns=\"" else " xmlns:$boundPrefix=\"")
            writeEscaped(namespaceURI, inAttribute = true)
            writer.write("\"")
        }

        declareIfUnbound(element.prefix, element.namespaceURI)
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index) as Attr
            // An attribute without a prefix is in no namespace, whatever the default namespace is.
            if (attribute.prefix != null) declareIfUnbound(attribute.prefix, attribute.namespaceURI)
        }

        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index) as Attr
            writer.write(" ")
            writer.write(attribute.name)
            writer.write("=\"")
            writeEscaped(attribute.value, inAttribute = true)
            writer.write("\"")
        }

        var child = element.firstChild
        if (child == null) {
            writer.write("/>")
        } else {
            writer.write(">")
            while (child != null) {
                writeNode(child)
                child = child.nextSibling
            }
            writer.write("</")
            writer.write(element.tagName)
            writer.write(">")
        }

        for ((prefix, namespaceURI) in previousBindings.asReversed()) {
            if (namespaceURI == null) boundNamespaces.remove(prefix) else boundNamespaces[prefix] = namespaceURI
        }
    }

    private fun writeDocumentType(documentType: DocumentType) {
        writer.write("<!DOCTYPE ")
        writer.write(documentType.name)
        val publicId = documentType.publicId
        val systemId = documentType.systemId
        if (!publicId.isNullOrEmpty()) {
            writer.write(" PUBLIC \"$publicId\" \"${systemId.orEmpty()}\"")
        } else if (!systemId.isNullOrEmpty()) {
            writer.write(" SYSTEM \"$systemId\"")
        }
        if (!documentType.internalSubset.isNullOrEmpty()) {
            writer.write(" [")
            writer.write(documentType.internalSubset)
            writer.write("]")
        }
        writer.write(">")
    }

    /**
     * Writes [text] with the characters escaped that XML requires, writing the runs between them at once.
     * In an attribute, white space other than a space is also escaped, which a parser would otherwise
     * normalize to a space.
     */
    private fun writeEscaped(text: String, inAttribute: Boolean) {
        var runStart = 0
        for (index in text.indices) {
            val replacement = when (text[index]) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> if (inAttribute) "&quot;" else null
                '\n' -> if (inAttribute) "&#10;" else null
                '\t' -> if (inAttribute) "&#9;" else null
                '\r' -> "&#13;"
                else -> null
            } ?: continue

            if (index > runStart) writer.write(text, runStart, index - runStart)
            writer.write(replacement)
            runStart = index + 1
        }
        if (runStart < text.length) writer.write(text, runStart, text.length - runStart)
    }

    private companion object {
        private const val XMLNS_NAMESPACE_URI = "http://www.w3.org/2000/xmlns/"
    }
}
