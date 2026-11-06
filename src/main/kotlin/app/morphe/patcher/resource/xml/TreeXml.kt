/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.xml

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.xml.ResourceXml
import app.morphe.patcher.xml.XmlElement
import com.reandroid.xml.CloseableParser
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.Writer

/**
 * A comment of a [TreeDocument], kept so that writing a document back keeps it.
 */
internal class TreeComment(val text: String)

/**
 * A document held as a tree of [TreeElement], which text XML and values files are read into.
 */
internal abstract class TreeDocument : ResourceXml {
    /**
     * Whether the document was closed, after which neither it nor its elements may be used.
     */
    var closed = false
        private set

    /**
     * Whether anything of the document changed since it was read.
     */
    var modified = false
        private set

    internal open fun createElement(tag: String) = TreeElement(this, tag)

    /**
     * Called when [element] changed: its tag, an attribute, its text, or which children it has.
     */
    internal open fun onChanged(element: TreeElement) {
        modified = true
    }

    /**
     * Called when [element] was removed from [parent].
     */
    internal open fun onRemoved(element: TreeElement, parent: TreeElement) = onChanged(parent)

    internal fun checkOpen() {
        if (closed) throw IllegalStateException("The XML document was closed")
    }

    final override fun close() {
        if (closed) return
        closed = true
        onClose()
    }

    /**
     * Keeps the changes of the document, once it was closed.
     */
    protected abstract fun onClose()
}

/**
 * An element of a [TreeDocument]. Attributes are held by their name as written, with the prefix, and
 * namespace declarations are held as the `xmlns` attributes they are written as.
 */
internal open class TreeElement(
    internal val owner: TreeDocument,
    tag: String,
) : ElementBase() {
    internal var parentElement: TreeElement? = null

    /** The attributes by their name as written, in the order of the element. */
    internal val attributes = LinkedHashMap<String, String>()

    /** [TreeElement], [String] for text, and [TreeComment]. */
    private val nodes = ArrayList<Any>()

    /**
     * The children of this element, loaded first if the document loads them when they are needed.
     */
    internal open fun nodeList(): MutableList<Any> = nodes

    override var tag: String = tag
        set(value) {
            checkUsable()
            if (field == value) return
            field = value
            owner.onChanged(this)
        }

    override val parent: XmlElement?
        get() = parentElement

    override val children: List<XmlElement>
        get() {
            checkUsable()
            return nodeList().filterIsInstance<TreeElement>()
        }

    override val index: Int
        get() = parentElement?.nodeList()?.asSequence()?.filterIsInstance<TreeElement>()?.indexOf(this) ?: -1

    override var text: String
        get() {
            checkUsable()
            return buildString { appendTextTo(this) }
        }
        set(value) {
            checkUsable()
            removeAllNodes()
            if (value.isNotEmpty()) nodeList() += value
            owner.onChanged(this)
        }

    private fun appendTextTo(builder: StringBuilder) {
        nodeList().forEach { node ->
            when (node) {
                is String -> builder.append(node)
                is TreeElement -> node.appendTextTo(builder)
            }
        }
    }

    override val attributeNames: List<String>
        get() {
            checkUsable()
            return attributes.keys.toList()
        }

    override fun attribute(name: String): String? {
        checkUsable()
        return attributes[name]
    }

    override fun setAttribute(name: String, value: String) {
        checkUsable()
        if (attributes.put(name, value) != value) owner.onChanged(this)
    }

    override fun removeAttribute(name: String) {
        checkUsable()
        if (attributes.remove(name) != null) owner.onChanged(this)
    }

    override fun insertChild(index: Int, tag: String, init: XmlElement.() -> Unit): XmlElement {
        checkUsable()
        val element = owner.createElement(tag)
        insertNode(index, element)
        element.init()
        return element
    }

    /**
     * Inserts an element that is not in any document yet at a position among the child elements.
     */
    internal fun insertNode(index: Int, element: TreeElement) {
        val list = nodeList()
        val position = nodePosition(list, index)
        element.parentElement = this
        list.add(position, element)
        owner.onChanged(this)
    }

    private fun nodePosition(list: List<Any>, elementIndex: Int): Int {
        var elements = 0
        list.forEachIndexed { position, node ->
            if (node is TreeElement) {
                if (elements == elementIndex) return position
                elements++
            }
        }
        if (elementIndex != elements) {
            throw IndexOutOfBoundsException("Index $elementIndex, but the element has $elements child elements")
        }
        return list.size
    }

    override fun remove() {
        checkUsable()
        val parent = parentElement ?: throw PatchException("The root element cannot be removed")
        parent.nodeList().remove(this)
        parentElement = null
        owner.onRemoved(this, parent)
    }

    override fun removeChildren() {
        checkUsable()
        if (nodeList().isEmpty()) return
        removeAllNodes()
        owner.onChanged(this)
    }

    private fun removeAllNodes() {
        val list = nodeList()
        list.forEach { if (it is TreeElement) it.parentElement = null }
        list.clear()
    }

    override fun contentNodes(): List<Any> = nodeList().filter { it !is TreeComment }

    override fun namespaceUri(prefix: String): String? {
        val declaration = if (prefix.isEmpty()) XMLNS_PREFIX else "$XMLNS_PREFIX:$prefix"
        var element: TreeElement? = this
        while (element != null) {
            element.attributes[declaration]?.let { return it }
            element = element.parentElement
        }
        return null
    }

    override fun appendText(text: String) {
        val list = nodeList()
        val last = list.lastOrNull()
        if (last is String) list[list.size - 1] = last + text else list += text
        owner.onChanged(this)
    }

    override fun setCopiedAttribute(name: String, value: String, namespaceUri: String?) {
        if (name == XMLNS_PREFIX || name.startsWith("$XMLNS_PREFIX:")) {
            // Declared only where the copy does not already have it.
            if (namespaceUri(name.substringAfter(':', "")) != value) setAttribute(name, value)
            return
        }
        val prefix = name.substringBefore(':', "")
        if (prefix.isNotEmpty() && namespaceUri != null && namespaceUri(prefix) != namespaceUri) {
            setAttribute("$XMLNS_PREFIX:$prefix", namespaceUri)
        }
        setAttribute(name, value)
    }

    override fun checkUsable() = owner.checkOpen()

    /**
     * Adds a node read from text, without notifying the document of a change.
     */
    internal fun addParsedNode(node: Any) {
        if (node is TreeElement) node.parentElement = this
        val list = nodes
        val last = list.lastOrNull()
        if (node is String && last is String) list[list.size - 1] = last + node else list += node
    }

    /**
     * The nodes as they are, without loading anything.
     */
    internal fun rawNodes(): MutableList<Any> = nodes
}

/**
 * Reads XML text into elements of [document] with the pull parser ARSCLib ships, which is the same on
 * every platform and much faster than a DOM parser on Android. Namespaces are not processed, so names keep
 * the prefix they are written with and declarations stay `xmlns` attributes, as the patcher's DOM does it.
 *
 * @return The root element.
 */
internal fun TreeDocument.parseTree(input: InputStream): TreeElement {
    val parser = CloseableParser()
    parser.setInput(input, null)

    var root: TreeElement? = null
    var current: TreeElement? = null
    var event = parser.nextToken()
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                val element = createElement(parser.name)
                for (index in 0 until parser.attributeCount) {
                    element.attributes[parser.getAttributeName(index)] = parser.getAttributeValue(index)
                }
                val parent = current
                if (parent == null) {
                    if (root != null) throw PatchException("XML has more than one root element")
                    root = element
                } else {
                    parent.addParsedNode(element)
                }
                current = element
            }

            XmlPullParser.END_TAG -> current = current?.parentElement

            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE ->
                parser.text?.let { text -> current?.addParsedNode(text) }

            XmlPullParser.COMMENT -> current?.addParsedNode(TreeComment(parser.text))
        }
        event = parser.nextToken()
    }

    return root ?: throw PatchException("XML has no root element")
}

/**
 * Writes the element and everything below it as UTF-8 XML, with an XML declaration.
 */
internal fun TreeElement.writeDocument(writer: Writer) {
    writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    writeElement(writer)
    writer.write("\n")
}

private fun TreeElement.writeElement(writer: Writer) {
    writer.write("<")
    writer.write(tag)
    attributes.forEach { (name, value) ->
        writer.write(" ")
        writer.write(name)
        writer.write("=\"")
        writeEscaped(writer, value, inAttribute = true)
        writer.write("\"")
    }

    val nodes = nodeList()
    if (nodes.isEmpty()) {
        writer.write("/>")
        return
    }

    writer.write(">")
    nodes.forEach { node ->
        when (node) {
            is TreeElement -> node.writeElement(writer)
            is String -> writeEscaped(writer, node, inAttribute = false)
            is TreeComment -> {
                writer.write("<!--")
                writer.write(node.text)
                writer.write("-->")
            }
        }
    }
    writer.write("</")
    writer.write(tag)
    writer.write(">")
}

/**
 * Writes [text] with the characters escaped that XML requires. In an attribute, white space other than
 * a space is also escaped, which a parser would otherwise normalize to a space.
 */
private fun writeEscaped(writer: Writer, text: String, inAttribute: Boolean) {
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
