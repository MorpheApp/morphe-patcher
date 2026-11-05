/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.xml

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.xml.XmlElement

internal const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
internal const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
internal const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
internal const val XMLNS_PREFIX = "xmlns"

/**
 * What the elements of every kind of document share, which is what copying an element from one kind
 * of document to another needs.
 */
internal abstract class ElementBase : XmlElement {
    /**
     * The children of this element in document order: elements, and text as strings.
     */
    internal abstract fun contentNodes(): List<Any>

    /**
     * The namespace URI [prefix] is bound to at this element, or null if it is not bound.
     */
    internal abstract fun namespaceUri(prefix: String): String?

    /**
     * Adds text after the children of this element.
     */
    internal abstract fun appendText(text: String)

    /**
     * Sets an attribute copied from another document.
     *
     * @param namespaceUri The namespace the prefix of [name] is bound to in the source document, if any.
     */
    internal abstract fun setCopiedAttribute(name: String, value: String, namespaceUri: String?)

    /**
     * Throws if this element can no longer be used.
     */
    internal open fun checkUsable() {}

    override fun elementByAttribute(name: String, value: String): XmlElement =
        elementByAttributeOrNull(name, value) ?: throw PatchException("Could not find an element with $name=\"$value\"")

    override fun insertCopy(index: Int, source: XmlElement): XmlElement {
        val copy = insertChild(index, source.tag) as ElementBase
        copy.copyContentFrom(source as ElementBase)
        return copy
    }

    override fun copyAttributesFrom(source: XmlElement) {
        source as ElementBase
        source.attributeNames.forEach { name ->
            source.attribute(name)?.let { value -> setCopiedAttribute(name, value, source.namespaceUriOf(name)) }
        }
    }

    internal fun copyContentFrom(source: ElementBase) {
        copyAttributesFrom(source)
        source.contentNodes().forEach { node ->
            when (node) {
                is String -> appendText(node)
                is ElementBase -> (insertChild(children.size, node.tag) as ElementBase).copyContentFrom(node)
            }
        }
    }

    /**
     * The namespace URI of the prefix of the attribute [name], or null if it has none.
     */
    internal fun namespaceUriOf(name: String): String? {
        val prefix = name.substringBefore(':', "")
        if (prefix.isEmpty() || prefix == XMLNS_PREFIX) return null
        return namespaceUri(prefix)
    }

    override fun toString() = "<$tag>"
}
