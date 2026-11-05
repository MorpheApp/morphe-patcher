/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.xml

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.coder.DecodablePackage
import app.morphe.patcher.resource.coder.OnRequestDecoder
import app.morphe.patcher.resource.utf8Writer
import app.morphe.patcher.xml.ResourceXml
import java.io.File

internal const val MANIFEST_PATH = "AndroidManifest.xml"

/**
 * Values files that are bookkeeping of the resource table rather than resources, which are always read as text.
 */
private val TEXT_VALUES_FILES = setOf("public.xml", "ids.xml", "overlayable.xml")

/**
 * What [ResourceXmlStore] needs of the resource coder that decodes the files of the APK.
 */
internal interface ResourceXmlHost {
    /** Decodes resources when patches request them, or null if every resource was decoded up front. */
    val onRequestDecoder: OnRequestDecoder?

    /** The package name of the APK. */
    val packageName: String

    /** The path a patch names a file by, for the name of its archive entry. */
    fun aliasOf(path: String): String

    /** Gets a file of the working directory as `resourceFile` does, decoding it if [copy] is set. */
    fun file(path: String, packageName: String, copy: Boolean): File

    /** The decoded manifest, which is decoded first if it is not yet. */
    fun decodedManifest(): File

    /** Where the manifest is decoded to, whether it is decoded or not. */
    val manifestFile: File

    /** Whether the manifest was decoded to text. */
    val manifestDecoded: Boolean

    /** Called once the manifest a patch changed in its binary form was written out as text. */
    fun onManifestWritten()

    /** Whether a patch deleted the resource file that was decoded to, or would be decoded to, [file]. */
    fun isDeleted(file: File): Boolean

    /**
     * Records [file] as a decoded file a patch changed, so that it is encoded again but is not taken for a
     * file a patch added.
     */
    fun markChanged(file: File)

    /** Decodes values files of a package, merging what was written to them before. */
    fun decodeValues(decodablePackage: DecodablePackage, paths: List<String>)

    /** Called once no document is open any more, so that the resource table can be released. */
    fun onDocumentsClosed()
}

/**
 * Opens the XML files of the APK for patches, and holds what they changed of compiled XML files and values
 * files until the APK is written. Each file is held by one owner at a time: a compiled file or a values
 * file that a patch changed is held here until it is written out as text, which happens as soon as anything
 * reads it as a file, and from then on it is read as text like any decoded file.
 */
internal class ResourceXmlStore(private val host: ResourceXmlHost) {
    private data class Key(val packageName: String, val path: String)

    private val binary = LinkedHashMap<Key, BinaryXmlState>()
    private val values = LinkedHashMap<Key, ValuesEdits>()
    private val open = HashSet<Key>()

    /**
     * How many documents are open, which need the resource table to stay loaded. Read without the lock of
     * this store, as the coder reads it while it holds its own lock.
     */
    @Volatile
    private var openCount = 0

    /** Whether a document is open, which needs the resource table to stay loaded. */
    val hasOpenDocuments: Boolean
        get() = openCount > 0

    /**
     * Opens an XML file of the APK.
     *
     * @param copy False to open a values file only to add or replace resources, without reading it.
     * @return The document, or null if there is no such file.
     */
    @Synchronized
    fun open(path: String, packageName: String?, copy: Boolean): ResourceXml? {
        val alias = host.aliasOf(path)
        if (alias == MANIFEST_PATH) {
            require(copy) { "The manifest cannot be opened without reading it" }
            return openManifest()
        }

        val pkg = packageName ?: host.packageName
        val decoder = host.onRequestDecoder
        val isValues = isValuesPath(alias)
        if (!copy && !isValues) throw IllegalArgumentException("Only a values file can be opened without reading it: $path")

        if (decoder == null || !alias.startsWith("res/")) return openText(Key(pkg, alias), host.file(path, pkg, copy), isValues)

        val decodablePackage = decoder.packages[pkg] ?: throw PatchException("Package $pkg not found")
        val key = Key(pkg, alias)
        val file = decodablePackage.directory.resolve(alias)

        binary[key]?.let { state ->
            state.bind(decoder.packageBlockOf(decodablePackage))
            return openBinary(key, state)
        }
        values[key]?.let { edits -> return openValues(key, decoder, decodablePackage, edits, copy) }

        // A decoded file, or one a patch wrote, is read as text.
        if (file.isFile || host.isDeleted(file)) return openText(key, file, isValues && !copy)

        if (alias in decodablePackage.resourceFiles) {
            decoder.binaryXml(decodablePackage, alias)?.let { state ->
                binary[key] = state
                return openBinary(key, state)
            }
            return openText(key, host.file(alias, pkg, true), false)
        }

        if (isValues) {
            if (alias.substringAfterLast('/') in TEXT_VALUES_FILES) return openText(key, host.file(alias, pkg, copy), !copy)
            if (copy && alias !in decodablePackage.valuesFiles) return null
            val edits = values.getOrPut(key) { ValuesEdits(pkg, alias) }
            return openValues(key, decoder, decodablePackage, edits, copy)
        }

        return null
    }

    private fun openManifest(): ResourceXml {
        val key = Key("", MANIFEST_PATH)
        val decoder = host.onRequestDecoder
        if (decoder == null || host.manifestDecoded) return openText(key, host.decodedManifest(), false)!!

        val state = binary.getOrPut(key) { decoder.manifestXml() }
        state.bind(decoder.manifestPackageBlock())
        return openBinary(key, state)
    }

    private fun claim(key: Key) {
        if (!open.add(key)) {
            throw IllegalStateException("${key.path} is already open. Close it before opening it again.")
        }
        openCount = open.size
    }

    private fun openBinary(key: Key, state: BinaryXmlState): ResourceXml {
        claim(key)
        return BinaryXmlDocument(state) {
            // A document that is not open does not keep the resource table, which may be released.
            state.bind(null)
            closed(key)
        }
    }

    private fun openValues(
        key: Key,
        decoder: OnRequestDecoder,
        decodablePackage: DecodablePackage,
        edits: ValuesEdits,
        copy: Boolean,
    ): ResourceXml {
        claim(key)
        val table = if (copy) decodablePackage.valuesFiles[key.path]?.let { decoder.valuesTable(decodablePackage, it) } else null
        return ValuesXmlDocument(table, edits) {
            synchronized(this) {
                if (edits.isEmpty) values.remove(key)
                closed(key)
            }
        }
    }

    /**
     * @param create Whether to create an empty values file if there is none.
     */
    private fun openText(key: Key, file: File, create: Boolean): ResourceXml? {
        if (!file.isFile) {
            if (!create) return null
            file.parentFile?.mkdirs()
            file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
        }
        claim(key)
        return TextXmlDocument.read(file) { closed(key) }
    }

    private fun closed(key: Key) {
        synchronized(this) {
            open.remove(key)
            openCount = open.size
            binary[key]?.let { state -> if (!state.modified) binary.remove(key) }
            if (open.isNotEmpty()) return
        }
        host.onDocumentsClosed()
    }

    /**
     * Writes out what is held of the file of a package at [path], so that it can be read as a file. A
     * directory only holds what was written out, as it only holds the files that were decoded, so writing
     * out the files below it is left to [writeOutMatching], which decoding them calls.
     */
    @Synchronized
    fun writeOut(path: String, packageName: String?) {
        val alias = host.aliasOf(path)
        if (alias == MANIFEST_PATH) {
            writeOutManifest()
            return
        }
        val pkg = packageName ?: host.packageName
        writeOutWhere { it.packageName == pkg && it.path == alias }
    }

    /**
     * Writes out what is held of the files of a package whose path matches.
     */
    @Synchronized
    fun writeOutMatching(packageName: String?, matches: (String) -> Boolean) {
        val pkg = packageName ?: host.packageName
        writeOutWhere { it.packageName == pkg && matches(it.path) }
    }

    /**
     * Writes out everything held, which encoding every resource from decoded files needs.
     *
     * @return The files written, which are changed resource files.
     */
    @Synchronized
    fun writeOutAll(): List<File> {
        writeOutManifest()
        return writeOutWhere { true }
    }

    /**
     * Writes the manifest out as text, if a patch changed it in its binary form.
     *
     * @return Whether it was written.
     */
    @Synchronized
    fun writeOutManifest(): Boolean {
        val key = Key("", MANIFEST_PATH)
        val state = binary[key] ?: return false
        checkNotOpen(key)
        binary.remove(key)
        val decoder = host.onRequestDecoder ?: return false
        state.bind(decoder.manifestPackageBlock())
        state.writeText(host.manifestFile)
        host.onManifestWritten()
        return true
    }

    private fun checkNotOpen(key: Key) {
        if (key in open) throw IllegalStateException("${key.path} is open, so it cannot be read as a file. Close it first.")
    }

    private fun writeOutWhere(matches: (Key) -> Boolean): List<File> {
        val decoder = host.onRequestDecoder ?: return emptyList()
        val written = mutableListOf<File>()

        binary.keys.filter { it.path != MANIFEST_PATH && matches(it) }.forEach { key ->
            checkNotOpen(key)
            val state = binary.remove(key)!!
            val decodablePackage = decoder.packages.getValue(key.packageName)
            state.bind(decoder.packageBlockOf(decodablePackage))
            val file = decodablePackage.directory.resolve(key.path)
            state.writeText(file)
            host.markChanged(file)
            written += file
        }

        values.keys.filter(matches).forEach { key ->
            checkNotOpen(key)
            val edits = values.remove(key)!!
            val decodablePackage = decoder.packages.getValue(key.packageName)
            written += writeOutValues(decodablePackage, edits)
        }

        return written
    }

    /**
     * Writes values edits out as the file a patch would have written before the file was decoded, which
     * decoding it merges into it, and removes the resources patches removed.
     */
    private fun writeOutValues(decodablePackage: DecodablePackage, edits: ValuesEdits): File {
        val file = decodablePackage.directory.resolve(edits.path)
        file.parentFile.mkdirs()
        val document = TextXmlDocument.detached("resources")
        edits.elements.values.forEach { document.root.insertNode(document.root.children.size, it.copyTo(document)) }
        file.utf8Writer().use { document.root.writeDocument(it) }

        if (edits.path in decodablePackage.valuesFiles) {
            host.decodeValues(decodablePackage, listOf(edits.path))
            if (edits.removed.isNotEmpty()) {
                TextXmlDocument.read(file).use { decoded ->
                    decoded.root.children.filter { it.attribute("name") in edits.removed }.forEach { it.remove() }
                }
            }
        }
        return file
    }

    /**
     * Forgets what is held of the files of a package at [path] or below it, which a patch deletes.
     */
    @Synchronized
    fun forget(path: String, packageName: String?) {
        val alias = host.aliasOf(path)
        val pkg = packageName ?: host.packageName
        val matches = { key: Key -> key.packageName == pkg && (key.path == alias || key.path.startsWith("${alias.trimEnd('/')}/")) }
        binary.keys.removeAll { matches(it) && it !in open }
        values.keys.removeAll { matches(it) && it !in open }
    }

    /**
     * The compiled files patches changed, other than the manifest.
     */
    @Synchronized
    fun changedResourceFiles(): List<BinaryXmlState> =
        binary.filter { (key, state) -> key.path != MANIFEST_PATH && state.modified }.values.sortedBy { it.path }

    /**
     * The manifest, if a patch changed it in its binary form.
     */
    @Synchronized
    fun changedManifest(): BinaryXmlState? = binary[Key("", MANIFEST_PATH)]?.takeIf { it.modified }

    /**
     * The values files patches changed, sorted by path.
     */
    @Synchronized
    fun changedValues(): List<ValuesEdits> = values.values.filter { !it.isEmpty }.sortedBy { it.path }

    /**
     * Reads the manifest, from whichever form holds it, without decoding it.
     */
    fun <T> readManifest(block: (ResourceXml) -> T): T = open(MANIFEST_PATH, null, true)!!.use(block)

    companion object {
        /**
         * Whether [path] is a values file, such as `res/values-de/strings.xml`.
         */
        fun isValuesPath(path: String): Boolean {
            if (!path.startsWith("res/") || !path.endsWith(".xml")) return false
            val directory = path.substringBeforeLast('/')
            if (directory.count { it == '/' } != 1) return false
            val name = directory.substringAfter('/')
            return name == "values" || name.startsWith("values-")
        }
    }
}

/**
 * Writes the document out as text, as the decoder writes a compiled file, with pending values as they
 * were set.
 */
internal fun BinaryXmlState.writeText(file: File) {
    val target = TextXmlDocument.detached(document.documentElement?.getName(true) ?: throw PatchException("$path has no root element"))
    val source = BinaryXmlDocument(this) {}
    try {
        val sourceRoot = source.root as BinaryElement
        sourceRoot.declarations().forEach { (name, uri) -> target.root.attributes[name] = uri }
        target.root.copyContentFrom(sourceRoot)
    } finally {
        source.close()
    }
    file.parentFile?.mkdirs()
    file.utf8Writer().use { target.root.writeDocument(it) }
}
