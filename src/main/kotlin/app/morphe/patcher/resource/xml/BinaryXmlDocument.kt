/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.xml

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.xml.ResourceXml
import app.morphe.patcher.xml.XmlElement
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.chunk.xml.ResXmlStartNamespace
import com.reandroid.arsc.chunk.xml.ResXmlTextNode
import com.reandroid.arsc.coder.ValueCoder
import com.reandroid.arsc.coder.XmlSanitizer
import com.reandroid.arsc.value.ValueType
import java.io.IOException
import java.util.IdentityHashMap

/**
 * A value a patch set that cannot be encoded once the APK is written, such as a reference to a resource
 * no patch added.
 */
internal class XmlValueException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * A compiled XML file of the APK, such as a layout or the manifest, held in its binary form from when a
 * patch first opens it until the APK is written.
 *
 * @param path The path patches open the file by, such as `res/layout/main.xml`.
 * @param archiveName The name of the archive entry of the file, which release builds often obfuscate.
 * @param packageName The package of the resource table the file belongs to.
 */
internal class BinaryXmlState(
    val path: String,
    val archiveName: String,
    val packageName: String,
    val document: ResXmlDocument,
) {
    /**
     * Attribute values that reference a resource that has no id yet, such as one a patch adds or an
     * `@+id/` declaration. They are held as text, and encoded once the new resources have ids.
     */
    val pending = IdentityHashMap<ResXmlAttribute, String>()

    /**
     * Whether a patch changed the document.
     */
    var modified = false

    /**
     * Binds the document to the package its references are encoded and decoded with, or unbinds it if
     * [packageBlock] is null, so that it does not keep a released resource table in memory.
     */
    fun bind(packageBlock: PackageBlock?) {
        document.packageBlock = packageBlock
    }

    /**
     * The names of the ids `@+id/` declarations of pending values declare, in document order.
     */
    fun declaredIds(): List<String> = attachedPending().mapNotNull { (_, value) ->
        if (value.startsWith(NEW_ID_PREFIX)) value.substring(NEW_ID_PREFIX.length) else null
    }.distinct()

    /**
     * The pending values of attributes that are still part of the document, in document order.
     */
    private fun attachedPending(): List<Pair<ResXmlAttribute, String>> {
        if (pending.isEmpty()) return emptyList()
        val attributes = document.recursiveAttributes()
        val result = mutableListOf<Pair<ResXmlAttribute, String>>()
        while (attributes.hasNext()) {
            val attribute = attributes.next()
            pending[attribute]?.let { result += attribute to it }
        }
        return result
    }

    /**
     * The bytes of the document with the pending values encoded, now that every resource has an id. The
     * document itself keeps them pending, so that it can still be written out as text if the resource table
     * is built again.
     *
     * @param packageBlock The package of the resource table the APK is written with.
     */
    fun encode(packageBlock: PackageBlock): ByteArray {
        document.refreshFull()
        val bytes = document.bytes
        val pendingValues = attachedPending()
        if (pendingValues.isEmpty()) return bytes

        // The copy lists its attributes in the same order, which pairs them with the pending values.
        val copy = if (document is AndroidManifestBlock) AndroidManifestBlock() else ResXmlDocument()
        copy.readBytes(bytes.inputStream())
        copy.packageBlock = packageBlock
        val positions = IdentityHashMap<ResXmlAttribute, Int>()
        document.recursiveAttributes().withIndex().forEach { (index, attribute) -> positions[attribute] = index }
        val copiedAttributes = copy.recursiveAttributes().asSequence().toList()

        pendingValues.forEach { (attribute, value) ->
            val target = copiedAttributes[positions.getValue(attribute)]
            val reference = if (value.startsWith(NEW_ID_PREFIX)) "@id/" + value.substring(NEW_ID_PREFIX.length) else value
            try {
                target.encode(target.uri, target.prefix, target.getName(false), reference, true)
            } catch (exception: IOException) {
                throw XmlValueException("$path: ${attribute.getName(true)}=\"$value\": ${exception.message}", exception)
            }
        }
        copy.refreshFull()
        return copy.bytes
    }

    companion object {
        const val NEW_ID_PREFIX = "@+id/"
    }
}

/**
 * A patch's view of a [BinaryXmlState] while it has the file open.
 *
 * @param onClosed Called once the document was closed.
 */
internal class BinaryXmlDocument(
    internal val state: BinaryXmlState,
    private val onClosed: (BinaryXmlDocument) -> Unit,
) : ResourceXml {
    private var closed = false

    private val elements = IdentityHashMap<ResXmlElement, BinaryElement>()

    override val root: XmlElement
        get() {
            checkOpen()
            val element = state.document.documentElement ?: throw PatchException("${state.path} has no root element")
            return wrap(element)
        }

    internal fun wrap(element: ResXmlElement): BinaryElement = elements.getOrPut(element) { BinaryElement(this, element) }

    internal fun checkOpen() {
        if (closed) throw IllegalStateException("The XML document ${state.path} was closed")
    }

    internal fun changed() {
        state.modified = true
    }

    override fun close() {
        if (closed) return
        closed = true
        elements.clear()
        onClosed(this)
    }
}

/**
 * An element of a [BinaryXmlDocument].
 */
internal class BinaryElement(
    private val owner: BinaryXmlDocument,
    private val element: ResXmlElement,
) : ElementBase() {
    private val state get() = owner.state

    override var tag: String
        get() {
            checkUsable()
            return element.getName(true)
        }
        set(value) {
            checkUsable()
            element.name = value
            owner.changed()
        }

    override val parent: XmlElement?
        get() {
            checkUsable()
            return element.parentElement?.let(owner::wrap)
        }

    override val children: List<XmlElement>
        get() {
            checkUsable()
            val result = mutableListOf<XmlElement>()
            element.elements.forEach { result += owner.wrap(it) }
            return result
        }

    override var text: String
        get() {
            checkUsable()
            return buildString { appendTextOf(element) }
        }
        set(value) {
            checkUsable()
            element.removeIf { true }
            if (value.isNotEmpty()) element.newText().text = value
            owner.changed()
        }

    private fun StringBuilder.appendTextOf(element: ResXmlElement) {
        for (node in element) {
            when (node) {
                is ResXmlTextNode -> if (!node.isComment) node.text?.let(::append)
                is ResXmlElement -> appendTextOf(node)
            }
        }
    }

    override val attributeNames: List<String>
        get() {
            checkUsable()
            // Namespace declarations read as the attributes they are written as, as in a decoded file.
            val names = declarations().mapTo(mutableListOf()) { it.first }
            element.attributes.forEach { names += it.qualifiedName() }
            return names
        }

    /**
     * The name of the attribute as the decoder writes it, with the prefix the document declares for its namespace.
     */
    private fun ResXmlAttribute.qualifiedName(): String {
        val local = decodeName(false) ?: getName(false)
        val uri = decodeUri() ?: return local
        // The prefix the file links the attribute to, unless it links it to another namespace.
        val linked = prefix?.takeIf { this.uri == uri }
        val prefix = linked ?: declaredPrefix(uri) ?: decodePrefix() ?: return local
        return "$prefix:$local"
    }

    private fun declaredPrefix(uri: String): String? {
        var current: ResXmlElement? = element
        while (current != null) {
            current.getNamespaceForUri(uri)?.prefix?.let { return it }
            current = current.parentElement
        }
        return null
    }

    /**
     * The namespaces this element declares, as the `xmlns` attributes they are written as.
     */
    internal fun declarations(): List<Pair<String, String>> {
        val declarations = mutableListOf<Pair<String, String>>()
        val parent = element.parentElement?.let(owner::wrap)
        element.namespaces.forEach { namespace ->
            // The decoder leaves out what nothing uses, such as the namespace of inlined resources, and
            // what an element above already declares.
            if ((namespace as? ResXmlStartNamespace)?.isUnused == true) return@forEach
            if (parent != null && parent.namespaceUri(namespace.prefix.orEmpty()) == namespace.uri) return@forEach
            val prefix = namespace.prefix
            declarations += (if (prefix.isNullOrEmpty()) XMLNS_PREFIX else "$XMLNS_PREFIX:$prefix") to namespace.uri
        }
        return declarations
    }

    override fun namespaceUri(prefix: String): String? {
        var current: ResXmlElement? = element
        while (current != null) {
            current.getNamespaceForPrefix(prefix)?.uri?.let { return it }
            current = current.parentElement
        }
        return if (prefix == "android") ANDROID_NAMESPACE else null
    }

    /**
     * The attribute written as [name]. An `android:` attribute is matched by the framework attribute it
     * names and any other prefixed attribute by the attribute of the app it names, whatever the prefix the
     * file declares, since compiled files are often stripped of their names. An attribute without a prefix is
     * matched by its name.
     */
    private fun findAttribute(name: String): ResXmlAttribute? {
        val prefix = name.substringBefore(':', "")
        val local = name.substringAfter(':')
        val prefixUri = if (prefix.isEmpty()) null else namespaceUri(prefix)
        element.attributes.forEach { attribute ->
            if (prefix.isEmpty()) {
                if (attribute.uri == null && attribute.getName(false) == local) return attribute
            } else if (attribute.decodeName(false) == local) {
                val uri = attribute.decodeUri()
                val matches = if (prefix == "android") {
                    uri == ANDROID_NAMESPACE
                } else {
                    uri != null && uri != ANDROID_NAMESPACE && (prefixUri == null || uri == prefixUri ||
                            attribute.nameId != 0)
                }
                if (matches) return attribute
            }
        }
        return null
    }

    override fun attribute(name: String): String? {
        checkUsable()
        if (name == XMLNS_PREFIX || name.startsWith("$XMLNS_PREFIX:")) {
            return declarations().firstOrNull { it.first == name }?.second
        }
        val attribute = findAttribute(name) ?: return null
        state.pending[attribute]?.let { return it }
        return attribute.textValue()
    }

    /**
     * The value of the attribute as the decoder writes it.
     */
    private fun ResXmlAttribute.textValue(): String? {
        if (valueType != ValueType.STRING) return decodeValue(true)
        var value = valueAsString ?: return null
        value = XmlSanitizer.escapeSpecialCharacter(value)
        if (nameId == 0 || resolveName() == null) value = XmlSanitizer.escapeDecodedValue(value)
        return value
    }

    override fun setAttribute(name: String, value: String) {
        checkUsable()
        setValue(name, value, null)
    }

    override fun removeAttribute(name: String) {
        checkUsable()
        setValue(name, null, null)
    }

    /**
     * Sets an attribute, or removes it if [value] is null.
     *
     * @param namespaceHint The namespace the prefix of [name] is bound to where the value comes from, if any.
     */
    private fun setValue(name: String, value: String?, namespaceHint: String?) {
        if (name == XMLNS_PREFIX || name.startsWith("$XMLNS_PREFIX:")) {
            // A declaration is added where the element needs it, and kept while anything uses it.
            if (value != null && namespaceUri(name.substringAfter(':', "")) != value) {
                element.getOrCreateNamespace(value, name.substringAfter(':', ""))
                owner.changed()
            }
            return
        }
        val existing = findAttribute(name)
        if (value == null) {
            if (existing != null) {
                state.pending.remove(existing)
                element.removeAttribute(existing)
                owner.changed()
            }
            return
        }

        val prefix = name.substringBefore(':', "")
        val local = name.substringAfter(':')
        val uri = when {
            prefix.isEmpty() -> null
            prefix == "android" -> ANDROID_NAMESPACE
            else -> namespaceHint ?: namespaceUri(prefix) ?: APP_NAMESPACE
        }
        // Build-time hints, which aapt leaves out of compiled files.
        if (uri == TOOLS_NAMESPACE) return
        val namespacePrefix = uri?.let { declaredPrefix(it) ?: prefix }

        val attribute = existing ?: element.newAttribute()
        try {
            if (isPendingReference(value)) {
                attribute.encodeAttributeName(uri, namespacePrefix, local)
                attribute.setValueAsString(value)
                state.pending[attribute] = value
            } else {
                attribute.encode(uri, namespacePrefix, local, value, true)
                state.pending.remove(attribute)
            }
        } catch (exception: IOException) {
            if (existing == null) element.removeAttribute(attribute)
            throw PatchException("${state.path}: cannot set $name=\"$value\": ${exception.message}", exception)
        }
        owner.changed()
    }

    /**
     * Whether [value] is a reference to a resource of the app that has no id yet, such as one a patch adds,
     * which can only be encoded once the APK is written.
     */
    private fun isPendingReference(value: String): Boolean {
        if (value.startsWith(BinaryXmlState.NEW_ID_PREFIX)) {
            val result = ValueCoder.encodeReference(state.document.packageBlock, value)
            return result == null || result.isError
        }
        if (value.length < 3 || (value[0] != '@' && value[0] != '?') || value.startsWith("@android:") ||
            value.startsWith("?android:")
        ) {
            return false
        }
        val result = ValueCoder.encodeReference(state.document.packageBlock, value) ?: return false
        return result.isError
    }

    override fun insertChild(index: Int, tag: String, init: XmlElement.() -> Unit): XmlElement {
        checkUsable()
        val created = element.newElementAt(nodePosition(index), tag)
        owner.changed()
        return owner.wrap(created).also { it.init() }
    }

    /**
     * The position among all nodes of the child element at [elementIndex], or after the last node.
     */
    private fun nodePosition(elementIndex: Int): Int {
        var elements = 0
        for (position in 0 until element.size()) {
            if (element.get(position) is ResXmlElement) {
                if (elements == elementIndex) return position
                elements++
            }
        }
        if (elementIndex != elements) {
            throw IndexOutOfBoundsException("Index $elementIndex, but the element has $elements child elements")
        }
        return element.size()
    }

    override fun remove() {
        checkUsable()
        if (element.parentElement == null) throw PatchException("The root element cannot be removed")
        element.removeSelf()
        owner.changed()
    }

    override fun removeChildren() {
        checkUsable()
        if (element.size() == 0) return
        element.removeIf { true }
        owner.changed()
    }

    override fun contentNodes(): List<Any> {
        val nodes = mutableListOf<Any>()
        for (node in element) {
            when (node) {
                is ResXmlElement -> nodes += owner.wrap(node)
                is ResXmlTextNode -> if (!node.isComment) node.text?.let { nodes += it }
            }
        }
        return nodes
    }

    override fun appendText(text: String) {
        element.newText().text = text
        owner.changed()
    }

    override fun setCopiedAttribute(name: String, value: String, namespaceUri: String?) {
        // A compiled file declares its namespaces itself.
        if (name == XMLNS_PREFIX || name.startsWith("$XMLNS_PREFIX:")) return
        setValue(name, value, namespaceUri)
    }

    override fun checkUsable() = owner.checkOpen()
}
