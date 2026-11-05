/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.xml

import app.morphe.patcher.resource.processor.StringsXmlEscapeProcessor
import app.morphe.patcher.resource.processor.StringsXmlUnEscapeProcessor
import app.morphe.patcher.xml.XmlElement
import com.reandroid.xml.XMLElement
import com.reandroid.xml.XMLText
import java.io.File

/**
 * The resources a values file declares, read from the resource table of the APK instead of a decoded file.
 */
internal interface ValuesTable {
    /**
     * The names of the resources of the file, in the order of the table.
     */
    fun names(): List<String>

    /**
     * Decodes resources of the file, every one in the order of the table if [names] is null.
     */
    fun decode(names: Collection<String>?): List<XMLElement>
}

/**
 * What patches changed of a values file, such as `res/values-de/strings.xml`, from when a patch first
 * opens it until the APK is written. Resources are recorded whole: a patch that changes an item of a
 * style records the style.
 *
 * @param path The path of the file, such as `res/values-de/strings.xml`.
 */
internal class ValuesEdits(val packageName: String, val path: String) {
    /** The type of the resources of the file, such as `string`. */
    val type: String = typeOfValuesPath(path)

    /** The qualifiers of the configuration of the file, such as `-de`, or empty for the default. */
    val qualifiers: String = qualifiersOfValuesPath(path)

    /** Resources patches added or changed, by name, in the order they were first added or changed. */
    val elements = LinkedHashMap<String, TreeElement>()

    /** Resources of the table patches removed. */
    val removed = LinkedHashSet<String>()

    val isEmpty get() = elements.isEmpty() && removed.isEmpty()

    val isStrings get() = isStringsFile(path)

    /**
     * The resources to encode, as the encoder reads them from a values file: the text of strings unescaped
     * as [StringsXmlUnEscapeProcessor] unescapes the strings files patches changed.
     */
    fun encodableElements(): List<XMLElement> = elements.values.map { it.toXmlElement(isStrings) }
}

/**
 * The type of the resources of a values file, as the encoder reads it from the name of the file.
 */
internal fun typeOfValuesPath(path: String): String {
    val name = path.substringAfterLast('/').removeSuffix(".xml")
    return if (name != "plurals" && name.endsWith("s")) name.dropLast(1) else name
}

/**
 * The qualifiers of a values file, such as `-de`, as the encoder reads them from its directory.
 */
internal fun qualifiersOfValuesPath(path: String): String {
    val directory = path.substringBeforeLast('/').substringAfterLast('/')
    val separator = directory.indexOf('-')
    return if (separator > 0) directory.substring(separator) else ""
}

private fun isStringsFile(path: String) = path.substringAfterLast('/') == "strings.xml"

/**
 * Elements whose white space only text is the indentation of their child elements, which the decoder writes.
 */
private val INDENTED_TAGS = setOf(
    "resources", "style", "array", "string-array", "integer-array", "plurals", "attr", "declare-styleable",
)

/**
 * A values file of the APK read from the resource table. Only the resources a patch looks up with
 * [resource] are decoded, unless it lists the children of the root, which decodes them all. What changed
 * is recorded in [edits] when the document is closed.
 *
 * @param table The resources of the file in the table, or null if the file is opened only to add or
 * replace resources.
 * @param edits What patches changed of the file before, which this document starts from and adds to.
 */
internal class ValuesXmlDocument(
    private val table: ValuesTable?,
    internal val edits: ValuesEdits,
    private val onClosed: (ValuesXmlDocument) -> Unit,
) : TreeDocument() {
    override val root: TreeElement = ValuesRoot()

    private val isStrings = edits.isStrings

    /** The resources decoded so far, by name. */
    private val loaded = HashMap<String, TreeElement>()

    /** The name each resource had when it was loaded, which a patch may change. */
    private val loadedNames = HashMap<TreeElement, String>()

    private var fullyLoaded = false

    /** Resources of the root that changed, in the order they first changed. */
    private val touched = LinkedHashSet<TreeElement>()

    /** Resources removed from the root. */
    private val removedElements = mutableListOf<TreeElement>()

    private val tableNames: Set<String> by lazy { table?.names()?.toHashSet().orEmpty() }

    private inner class ValuesRoot : TreeElement(this, "resources") {
        override fun nodeList(): MutableList<Any> {
            if (!fullyLoaded) loadAll()
            return rawNodes()
        }

        // Adding a resource does not need the others, which a large strings file has thousands of.
        override fun appendChild(tag: String, init: XmlElement.() -> Unit): XmlElement {
            if (fullyLoaded) return super.appendChild(tag, init)
            checkUsable()
            val element = createElement(tag)
            element.parentElement = this
            rawNodes() += element
            onChanged(this)
            element.init()
            return element
        }

        override fun appendCopy(source: XmlElement): XmlElement {
            if (fullyLoaded) return super.appendCopy(source)
            val copy = appendChild(source.tag) as ElementBase
            copy.copyContentFrom(source as ElementBase)
            return copy
        }
    }

    override fun resourceOrNull(name: String): XmlElement? {
        checkOpen()
        if (fullyLoaded) return root.rawNodes().firstOrNull { it is TreeElement && it.attribute("name") == name } as TreeElement?
        loaded[name]?.let { return it.takeIf { element -> element.parentElement != null } }
        // Added by this document, under a name it was not loaded with.
        root.rawNodes().firstOrNull { it is TreeElement && it !in loadedNames && it.attribute("name") == name }?.let {
            return it as TreeElement
        }
        if (name in edits.removed) return null

        val element = edits.elements[name]?.copyTo(this)
            ?: if (table != null && name in tableNames) table.decode(listOf(name)).firstOrNull()?.toTreeElement() else null
        element ?: return null
        load(name, element)
        root.rawNodes() += element
        return element
    }

    private fun load(name: String, element: TreeElement) {
        element.parentElement = root
        loaded[name] = element
        loadedNames[element] = name
    }

    /**
     * Loads every resource into the root: those of the table in its order, with what patches changed,
     * followed by the resources patches added.
     */
    private fun loadAll() {
        fullyLoaded = true
        val nodes = root.rawNodes()
        val current = nodes.toList()
        nodes.clear()

        // Resources this document added, which replace those of the table with their name.
        val added = current.filter { it is TreeElement && it !in loadedNames }.mapNotNullTo(HashSet()) {
            (it as TreeElement).attribute("name")
        }

        if (table != null) {
            val pending = tableNames.filter {
                it !in loaded && it !in edits.removed && it !in edits.elements && it !in added
            }.toSet()
            val decoded = if (pending.isEmpty()) emptyMap() else {
                table.decode(if (pending.size == tableNames.size) null else pending)
                    .map { it.toTreeElement() }.associateBy { it.attribute("name").orEmpty() }
            }
            table.names().forEach { name ->
                if (name in edits.removed || (name in added && name !in loaded)) return@forEach
                val element = loaded[name] ?: edits.elements[name]?.copyTo(this) ?: decoded[name] ?: return@forEach
                if (name !in loaded) load(name, element)
                if (element.parentElement != null) nodes += element
            }
        }

        edits.elements.forEach { (name, edited) ->
            if (name in tableNames || name in loaded || name in added) return@forEach
            load(name, edited.copyTo(this))
            nodes += loaded.getValue(name)
        }

        current.forEach { node -> if (node !in nodes && (node !is TreeElement || node.parentElement != null)) nodes += node }
    }

    override fun onChanged(element: TreeElement) {
        super.onChanged(element)
        topLevel(element)?.let(touched::add)
    }

    override fun onRemoved(element: TreeElement, parent: TreeElement) {
        super.onChanged(parent)
        if (parent === root) removedElements += element else topLevel(parent)?.let(touched::add)
    }

    /**
     * The resource [element] is part of, or null if it is the root.
     */
    private fun topLevel(element: TreeElement): TreeElement? {
        var current = element
        while (true) {
            val parent = current.parentElement ?: return null
            if (parent === root) return current
            current = parent
        }
    }

    override fun onClose() {
        removedElements.forEach { element ->
            val name = loadedNames[element] ?: element.attributes["name"] ?: return@forEach
            removeName(name)
        }
        touched.forEach { element ->
            if (element.parentElement !== root) return@forEach
            val name = element.attributes["name"] ?: return@forEach
            // A resource renamed by a patch is removed under its old name.
            loadedNames[element]?.let { if (it != name) removeName(it) }
            edits.elements.remove(name)
            edits.elements[name] = element
            edits.removed.remove(name)
        }
        onClosed(this)
    }

    private fun removeName(name: String) {
        edits.elements.remove(name)
        if (name in tableNames) edits.removed += name
    }

    /**
     * Turns a resource decoded from the table into an element of this document. Strings get the escaping
     * of [StringsXmlEscapeProcessor], which is the form patches see in decoded strings files.
     */
    private fun XMLElement.toTreeElement(): TreeElement {
        fun convert(source: XMLElement, inString: Boolean): TreeElement {
            val element = createElement(source.getName(false))
            for (attribute in source.attributes) element.attributes[attribute.getName(true)] = attribute.valueAsString
            val string = inString || (isStrings && element.tag == "string")
            for (node in source) {
                when (node) {
                    is XMLElement -> element.addParsedNode(convert(node, string))
                    is XMLText -> {
                        val text = node.text ?: continue
                        if (text.isBlank() && element.tag in INDENTED_TAGS) continue
                        element.addParsedNode(if (string) escapeString(withoutInvalidCharacters(text)) else text)
                    }
                }
            }
            return element
        }
        return convert(this, false)
    }

    private companion object {
        val escapeProcessor = StringsXmlEscapeProcessor({ _, _ -> File("") }, emptyMap())

        fun escapeString(text: String) = escapeProcessor.processString(text)
    }
}

private val unescapeProcessor = StringsXmlUnEscapeProcessor({ _, _ -> File("") }, emptyMap())

/**
 * The text without the characters XML cannot hold, which the decoder of strings files removes.
 */
private fun withoutInvalidCharacters(text: String): String {
    fun isValid(code: Int) = code == 0x9 || code == 0xA || code == 0xD || code in 0x20..0xD7FF ||
            code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF

    var index = 0
    while (index < text.length) {
        val code = text.codePointAt(index)
        if (!isValid(code)) break
        index += Character.charCount(code)
    }
    if (index == text.length) return text

    val builder = StringBuilder(text.length).append(text, 0, index)
    while (index < text.length) {
        val code = text.codePointAt(index)
        if (isValid(code)) builder.appendCodePoint(code)
        index += Character.charCount(code)
    }
    return builder.toString()
}

/**
 * A copy of the element and everything below it, as an element of [document].
 */
internal fun TreeElement.copyTo(document: TreeDocument): TreeElement {
    val copy = document.createElement(tag)
    copy.attributes.putAll(attributes)
    rawNodes().forEach { node ->
        copy.addParsedNode(if (node is TreeElement) node.copyTo(document) else node)
    }
    return copy
}

/**
 * The element as ARSCLib reads it from a values file.
 *
 * @param strings Whether the element is of a strings file, whose strings are unescaped first.
 */
internal fun TreeElement.toXmlElement(strings: Boolean): XMLElement {
    fun convert(source: TreeElement, target: XMLElement, inString: Boolean) {
        source.attributes.forEach { (name, value) -> target.setAttribute(name, value) }
        val string = inString || (strings && source.tag == "string")
        source.rawNodes().forEach { node ->
            when (node) {
                is TreeElement -> convert(node, target.newElement(node.tag), string)
                is String -> target.addText(if (string) unescapeProcessor.processString(node) else node)
            }
        }
    }
    return XMLElement(tag).also { convert(this, it, false) }
}
