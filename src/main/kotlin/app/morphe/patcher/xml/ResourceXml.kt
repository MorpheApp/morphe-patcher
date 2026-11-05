/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.xml

import app.morphe.patcher.patch.PatchException
import java.io.Closeable

/**
 * An XML file of the APK being patched, or XML read from a stream.
 *
 * Files of the APK are edited where they are, without decoding them to text first: compiled XML files
 * such as layouts and the manifest are edited in their binary form, and values files such as
 * `res/values/strings.xml` are edited in the resource table they are generated from. Everything else is
 * read and written as text.
 *
 * Changes are kept when the document is closed, which [use] does. A document must not be used after it
 * is closed, and neither may its elements.
 *
 * ```kt
 * resourceXml("res/layout/main.xml").use { xml ->
 *     xml.elementByAttribute("android:id", "@id/title").setAttribute("android:visibility", "gone")
 * }
 * ```
 */
interface ResourceXml : Closeable {
    /**
     * The root element.
     */
    val root: XmlElement

    /**
     * The root and every element below it, in document order.
     *
     * @param tag Only the elements with this tag, or every element if null.
     */
    fun elements(tag: String? = null): Sequence<XmlElement> = root.elements(tag)

    /**
     * The first element in document order whose attribute [name] has [value].
     *
     * @throws PatchException If no element has it.
     */
    fun elementByAttribute(name: String, value: String): XmlElement = root.elementByAttribute(name, value)

    /**
     * The first element in document order whose attribute [name] has [value], or null if no element has it.
     */
    fun elementByAttributeOrNull(name: String, value: String): XmlElement? = root.elementByAttributeOrNull(name, value)

    /**
     * The resource of a values file, such as the `<string>` of `res/values/strings.xml` with this name.
     * Unlike [elementByAttribute], this decodes only that resource of the resource table, not every
     * resource of the file.
     *
     * @throws PatchException If the file does not declare it.
     */
    fun resource(name: String): XmlElement =
        resourceOrNull(name) ?: throw PatchException("Could not find the resource $name")

    /**
     * The resource of a values file with this name, as [resource] finds it, or null if the file does not
     * declare it.
     */
    fun resourceOrNull(name: String): XmlElement? =
        root.children.firstOrNull { it.attribute("name") == name }
}

/**
 * An element of an [ResourceXml].
 *
 * Attributes are read and set as they are written in XML, such as `@id/title`, `match_parent` or
 * `16.0dip`, with the prefix the document declares for their namespace, such as `android:id`. An
 * attribute of a compiled XML file is checked when it is set: an attribute Android does not know,
 * or a value it does not accept, fails right away. A reference to a resource a patch adds is resolved
 * when the APK is written.
 */
interface XmlElement {
    /**
     * The tag, such as `LinearLayout` or `string`.
     */
    var tag: String

    /**
     * The parent element, or null for the root element.
     */
    val parent: XmlElement?

    /**
     * The child elements, in document order.
     */
    val children: List<XmlElement>

    /**
     * The child elements with this tag, in document order.
     */
    fun children(tag: String): List<XmlElement> = children.filter { it.tag == tag }

    /**
     * The position of this element among the child elements of its parent, or -1 for the root element.
     */
    val index: Int
        get() = parent?.children?.indexOf(this) ?: -1

    /**
     * The text of this element and of the elements below it. Setting it replaces the children of this
     * element with the text.
     */
    var text: String

    /**
     * The names of the attributes, such as `android:id`, in the order of the element.
     */
    val attributeNames: List<String>

    /**
     * The value of an attribute, or null if the element does not have it.
     */
    fun attribute(name: String): String?

    /**
     * Sets an attribute, adding it if the element does not have it.
     */
    fun setAttribute(name: String, value: String)

    /**
     * Removes an attribute, if the element has it.
     */
    fun removeAttribute(name: String)

    /**
     * Whether the element has the attribute.
     */
    fun hasAttribute(name: String): Boolean = attribute(name) != null

    /**
     * This element and every element below it, in document order.
     *
     * @param tag Only the elements with this tag, or every element if null.
     */
    fun elements(tag: String? = null): Sequence<XmlElement> = sequence {
        val pending = ArrayDeque<XmlElement>()
        pending.addLast(this@XmlElement)
        while (pending.isNotEmpty()) {
            val element = pending.removeFirst()
            if (tag == null || element.tag == tag) yield(element)
            element.children.asReversed().forEach(pending::addFirst)
        }
    }

    /**
     * The first element in document order, of this element and the elements below it, whose
     * attribute [name] has [value].
     *
     * @throws PatchException If no element has it.
     */
    fun elementByAttribute(name: String, value: String): XmlElement

    /**
     * The first element as [elementByAttribute] finds it, or null if no element has it.
     */
    fun elementByAttributeOrNull(name: String, value: String): XmlElement? =
        elements().firstOrNull { it.attribute(name) == value }

    /**
     * Adds a child element after the others.
     *
     * @param init Sets up the new element, for example its attributes.
     * @return The new element.
     */
    fun appendChild(tag: String, init: XmlElement.() -> Unit = {}): XmlElement =
        insertChild(children.size, tag, init)

    /**
     * Adds a child element at a position among the child elements.
     *
     * @param index The position, from 0 to the number of child elements.
     * @param init Sets up the new element, for example its attributes.
     * @return The new element.
     */
    fun insertChild(index: Int, tag: String, init: XmlElement.() -> Unit = {}): XmlElement

    /**
     * Adds a copy of an element and everything below it after the other children. The element can be
     * of any document, such as XML read with [app.morphe.patcher.patch.readXml].
     *
     * @return The copy.
     */
    fun appendCopy(source: XmlElement): XmlElement = insertCopy(children.size, source)

    /**
     * Adds a copy of an element and everything below it at a position among the child elements. The
     * element can be of any document.
     *
     * @param index The position, from 0 to the number of child elements.
     * @return The copy.
     */
    fun insertCopy(index: Int, source: XmlElement): XmlElement

    /**
     * Sets every attribute of [source] on this element.
     */
    fun copyAttributesFrom(source: XmlElement) {
        source.attributeNames.forEach { name -> source.attribute(name)?.let { setAttribute(name, it) } }
    }

    /**
     * Removes this element and everything below it from its parent.
     */
    fun remove()

    /**
     * Removes every child of this element, text included.
     */
    fun removeChildren()
}
