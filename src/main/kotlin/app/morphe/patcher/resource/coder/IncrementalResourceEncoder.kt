/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.logging.PhaseTimings
import app.morphe.patcher.logging.measure
import app.morphe.patcher.logging.toLogString
import app.morphe.patcher.patch.PatchException
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkUtil
import com.reandroid.apk.UncompressedFiles
import com.reandroid.apk.xmlencoder.EncodeUtil
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import app.morphe.patcher.resource.xml.BinaryXmlState
import app.morphe.patcher.resource.xml.ValuesEdits
import com.reandroid.archive.Archive
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.FileInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.coder.XmlSanitizer
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.arsc.coder.xml.XmlEncodeException
import com.reandroid.arsc.coder.xml.XmlEncodeUtil
import com.reandroid.arsc.list.OverlayableList
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ValueType
import com.reandroid.utils.io.IOUtil
import com.reandroid.xml.StyleDocument
import com.reandroid.xml.XMLElement
import com.reandroid.xml.XMLFactory
import com.reandroid.xml.XMLUtil
import com.reandroid.xml.source.XMLFileParserSource
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.logging.Logger
import kotlin.time.measureTime

/**
 * Encodes the resources a patch changed into the resource table of the input APK, instead of
 * rebuilding that table from every decoded values file.
 *
 * The table an app ships is the authority for everything a patch left alone. Only the values
 * files a patch touched are re-encoded. Each replaces the entries of its type in its
 * configuration, or only adds to them if the file was never decoded. Only new or changed
 * files under `res/` are added to the archive. The rest of the archive is the input APK, which
 * [module] already holds.
 *
 * @param module The input APK with its framework attached. It becomes the output.
 * @param workingDir The decoded working directory.
 * @param packageDirectories Original package name to the directory it was decoded to.
 * @param archiveNameOf The name of the archive entry a decoded `res/` file stands for.
 * @param isNewFile Whether a decoded file did not exist at decode time.
 * @param originalNameOf The archive entry name a decoded path stands for, or null if it is not an alias.
 * @param isAddition Whether a values file was written by a patch without being decoded. Such a file
 * only adds resources to its configuration or replaces them, as it never held the others.
 * @param timings The timings the steps of encoding are measured into, if any.
 */
internal class IncrementalResourceEncoder(
    private val module: ApkModule,
    private val workingDir: File,
    private val packageDirectories: Map<String, File>,
    private val archiveNameOf: (File) -> String,
    private val isNewFile: (File) -> Boolean,
    private val originalNameOf: (String) -> String?,
    private val isAddition: (File) -> Boolean = { false },
    private val timings: PhaseTimings? = null,
) {
    private val logger = Logger.getLogger(IncrementalResourceEncoder::class.java.name)

    /** Archive entry names this encoder added or replaced. */
    val encodedEntries = mutableSetOf<String>()

    /**
     * @param modifiedResources Decoded `res/` files a patch added or changed.
     * @param deletedEntries Archive entry names of resource files a patch deleted.
     * @param deletedValuesFiles Decoded values files a patch deleted, whose resources are gone
     * from their configuration. Patches strip translations this way.
     * @param patchedConfigurations Values directories of resource configurations patches added,
     * which are encoded sparse.
     * @param publicIds The resource ids to declare in the table, by type and name: every id of
     * `public.xml`, which includes ids patches declared there themselves, or only the ids given to
     * new resources when the ids were read from the table.
     * @param originalPackageName The package name of the app as decoded.
     * @param newPackageName The package name the manifest now declares.
     * @param packagesToEncode The names of the packages of [packageDirectories] to encode. The others
     * are left as the table has them.
     * @param onProgress Called with how many values files of a package are encoded.
     * @param heldValues Values files patches changed as documents, which were never written as files.
     * @param heldFiles Compiled resource files patches changed in their binary form.
     * @param heldManifest The manifest, if a patch changed it in its binary form and it was never decoded.
     */
    fun encode(
        modifiedResources: Set<File>,
        deletedEntries: Set<String>,
        deletedValuesFiles: Set<File>,
        patchedConfigurations: List<File>,
        publicIds: Map<Pair<String, String>, Int>,
        originalPackageName: String,
        newPackageName: String,
        packagesToEncode: Set<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        heldValues: List<ValuesEdits> = emptyList(),
        heldFiles: List<BinaryXmlState> = emptyList(),
        heldManifest: BinaryXmlState? = null,
    ) {
        val tableBlock = module.tableBlock ?: throw PatchException("The APK has no resource table")

        // New strings go at the end of the pools instead of the pools being sorted, so the strings
        // already there keep their index. Sorting a pool and updating every value that refers to
        // one of its strings was most of the time the table took.
        tableBlock.stringPool.isKeepStringOrder = true
        tableBlock.forEach { it.specStringPool.isKeepStringOrder = true }

        val valuesCoder = XmlCoder.getInstance().VALUES_XML
        val useSparseEntries = module.useSparseEntries()
        val patchedDirectories = patchedConfigurations.toSet()

        // An entry whose value names an archive entry is defined by that file. It was never in a
        // values file, so a values file that no longer declares it says nothing about it.
        val archive = module.zipEntryMap
        val isDefinedByFile = { entry: Entry ->
            val value = entry.resValue
            value?.valueType == ValueType.STRING && value.valueAsString?.let(archive::contains) == true
        }

        val uncompressedFiles = workingDir.resolve(UncompressedFiles.JSON_FILE)
        if (uncompressedFiles.isFile) module.uncompressedFiles.fromJson(uncompressedFiles)

        packageDirectories.forEach { (packageName, packageDirectory) ->
            if (packageName !in packagesToEncode) return@forEach
            val resDirectory = packageDirectory.resolve("res")

            val packageBlock = tableBlock.firstOrNull { it.name == packageName }
                ?: tableBlock.pickOne()
                ?: throw PatchException("No resource package for $packageName in the table")

            val registered = measureTime { timings.measure("declareIds") { packageBlock.declareIds(publicIds) } }
            if (packageName == originalPackageName && packageBlock.name != newPackageName) {
                packageBlock.name = newPackageName
            }

            // Sorted so the table comes out the same however the file system listed the files.
            val modified = modifiedResources.filter { it.isFile && it.startsWith(resDirectory) }
                .sortedBy { it.invariantSeparatorsPath }
            val (valuesFiles, resFiles) = modified.partition {
                ApkUtil.isValuesDirectoryName(it.parentFile.name, true)
            }
            val overlayable = resDirectory.resolve("values/overlayable.xml")
            val appValuesFiles = valuesFiles.filter {
                it.extension == "xml" && it.name != "public.xml" && it != overlayable &&
                        it.parentFile !in patchedDirectories
            }
            val configurations = patchedConfigurations.filter { it.startsWith(resDirectory) }
            val (heldConfigurations, packageHeldValues) = heldValues.filter { it.packageName == packageName }
                .partition { isPatchedConfiguration(it.qualifiers) }
            val packageHeldFiles = heldFiles.filter { it.packageName == packageName }

            var encoded = 0
            var declared = 0
            val values = measureTime {
                timings.measure("values") {
                    // Files and documents, ordered as if the documents were files, so the table comes out the same.
                    val units: List<Pair<String, Any>> = appValuesFiles.map { it.relativeTo(packageDirectory).invariantSeparatorsPath to it } +
                            packageHeldValues.map { it.path to it }
                    // Attributes first: styles and enums are encoded against attribute names.
                    val (attrs, others) = units.sortedBy { it.first }.partition { it.first.endsWith("/attrs.xml") }
                    val ordered = attrs + others
                    ordered.forEachIndexed { index, (_, unit) ->
                        val counts = if (unit is ValuesEdits) {
                            encodeValuesEdits(unit, packageBlock.getOrCreateTypeBlock(unit.qualifiers, unit.type), valuesCoder, isDefinedByFile)
                        } else {
                            val valuesFile = unit as File
                            val typeBlock = packageBlock.getOrCreateTypeBlock(
                                XmlEncodeUtil.getQualifiersFromValuesXml(valuesFile),
                                XmlEncodeUtil.getTypeFromValuesXml(valuesFile),
                            )
                            encodeValuesFile(valuesFile, typeBlock, valuesCoder, isDefinedByFile, isAddition(valuesFile))
                        }
                        encoded += counts.first
                        declared += counts.second
                        onProgress(index + 1, ordered.size)
                    }
                }

                timings.measure("deletedValues") {
                    deletedValuesFiles.filter { it.startsWith(resDirectory) }.forEach { valuesFile ->
                        val typeBlock = packageBlock.getSpecTypePair(XmlEncodeUtil.getTypeFromValuesXml(valuesFile))
                            ?.getTypeBlock(ResConfig.parse(XmlEncodeUtil.getQualifiersFromValuesXml(valuesFile)))
                            ?: return@forEach
                        typeBlock.listEntries(true).forEach { if (!isDefinedByFile(it)) it.empty() }
                    }
                }

                timings.measure("overlayable") {
                    if (overlayable in modifiedResources && overlayable.isFile) {
                        if (isAddition(overlayable)) {
                            // Adds to the overlayables the app declares, which the file never held.
                            packageBlock.overlayableList.parseAddition(overlayable)
                        } else {
                            packageBlock.overlayableList.clearChildes()
                            packageBlock.overlayableList.parse(XMLFactory.newPullParser(overlayable))
                        }
                    }
                }

                timings.measure("configurations") {
                    configurations.forEach { valuesDirectory ->
                        valuesDirectory.listFiles { file: File -> file.isFile && file.extension == "xml" }
                            .orEmpty().sortedBy { it.name }.forEach { valuesFile ->
                                val typeBlock = packageBlock.patchedTypeBlock(valuesFile, useSparseEntries)
                                valuesCoder.encode(XMLFactory.newPullParser(valuesFile), typeBlock)
                            }
                    }
                    heldConfigurations.forEach { edits ->
                        val typeBlock = packageBlock.patchedTypeBlock(packageDirectory.resolve(edits.path), useSparseEntries)
                        edits.encodableElements().forEach { valuesCoder.encodeEntry(it, typeBlock) }
                    }
                }
            }

            timings.measure("resFiles") {
                resFiles.forEach { file -> addResFile(packageBlock, file) }
                packageHeldFiles.forEach { state -> addHeldFile(packageBlock, state) }
                packageBlock.sortTypes()
            }

            // Most packages of a split or multi package table are untouched, so only log the ones that changed.
            val configurationCount = configurations.size + heldConfigurations.size
            if (appValuesFiles.isNotEmpty() || packageHeldValues.isNotEmpty() || resFiles.isNotEmpty() ||
                packageHeldFiles.isNotEmpty() || configurationCount != 0
            ) {
                logger.info(
                    "Encoded $encoded of $declared values in ${appValuesFiles.size + packageHeldValues.size} files, " +
                            "${resFiles.size + packageHeldFiles.size} resource files and $configurationCount " +
                            "configurations of patches for $packageName"
                )
            }
            logger.fine { "Resource table update timings for $packageName: ids=${registered.toLogString()}, values=${values.toLogString()}" }
        }

        // Empties the entries of a deleted file and drops its archive entry.
        timings.measure("deletedEntries") { deletedEntries.forEach { module.removeResFile(it, true) } }

        timings.measure("compactSparse") { tableBlock.forEach { it.compactSparseTypeBlocks() } }

        timings.measure("manifest") {
            val manifest = workingDir.resolve(AndroidManifestBlock.FILE_NAME)
            val packageBlock = tableBlock.firstOrNull { it.name == newPackageName } ?: tableBlock.pickOne()
            val source = if (manifest.isFile) {
                XMLEncodeSource(packageBlock, XMLFileParserSource(AndroidManifestBlock.FILE_NAME, manifest))
            } else {
                // A manifest no patch changed is not encoded, and stays as the input APK has it.
                heldManifest?.let { ByteInputSource(it.encode(packageBlock), AndroidManifestBlock.FILE_NAME) }
            }
            if (source != null) {
                source.method = Archive.STORED
                source.sort = 0
                module.add(source)
                encodedEntries += AndroidManifestBlock.FILE_NAME
            }
        }

        // The table starts as the input's, so the strings of emptied entries, such as a removed
        // locale's, would stay in the pool unreferenced. They are emptied rather than removed,
        // which would move every string after them. A styled one keeps its text for its spans.
        timings.measure("stringPool") { tableBlock.stringPool.listUnused().forEach { if (!it.hasStyle()) it.set("") } }

        // Written without this, the table's styled strings came out misaligned, although the
        // archive refreshes the table again when it writes it.
        timings.measure("refresh") { tableBlock.refresh() }
        encodedEntries += "resources.arsc"
    }

    /**
     * Brings the table's ids in line with `public.xml`, as ARSCLib's [PackageBlock.PublicXmlParser]
     * does: an id the table lacks gets an empty, named entry for the values file or resource file
     * that declares the resource to encode into, and an id whose name changed is renamed. An id
     * resource has no file to come from, so it is given its value here.
     */
    private fun PackageBlock.declareIds(publicIds: Map<Pair<String, String>, Int>) {
        publicIds.forEach { (typeAndName, resourceId) ->
            if ((resourceId ushr 24) != id) return@forEach
            val (type, name) = typeAndName

            getResource(resourceId)?.let { existing ->
                if (existing.name != name) existing.name = name
                return@forEach
            }

            val typeId = (resourceId shr 16) and 0xff
            getOrCreateTypeString(typeId, type)
            val entry = getOrCreateTypeBlock(typeId.toByte(), "").getOrCreateEntry(resourceId and 0xffff)
            entry.setName(name, true)
            if (type == "id") {
                entry.setValueAsBoolean(false)
                entry.header.isPublic = true
                entry.header.isWeak = true
            }
        }
    }

    /**
     * Parses overlayables a patch declares into those the app declares. They are parsed into the list of
     * the package, as their items are resolved against it, and one with the name of an overlayable the app
     * declares is merged into it.
     */
    private fun OverlayableList.parseAddition(file: File) {
        val declared = size()
        parse(XMLFactory.newPullParser(file))

        for (index in size() - 1 downTo declared) {
            val added = get(index)
            val existing = (0 until declared).map(::get).firstOrNull { it.name == added.name } ?: continue
            existing.merge(added)
            remove(index)
        }
    }

    /**
     * Takes emptied entries out of the sparse chunks. A sparse chunk lists only the resources it
     * defines, each as a pair of id and offset, so an emptied entry cannot stay behind as a pair
     * with no offset: the resource system reads that as the chunk's first entry, and every
     * resource a patch deleted would resolve to whichever file survived. This is also how
     * ARSCLib itself builds a sparse chunk, without its null entries.
     */
    private fun PackageBlock.compactSparseTypeBlocks() {
        listSpecTypePairs().forEach { specTypePair ->
            specTypePair.forEach { typeBlock ->
                if (!typeBlock.isSparse) return@forEach
                val entries = typeBlock.entryArray
                // The id of a sparse entry is read off its pair, so collect them before rebuilding.
                val definedIds = (0 until entries.size()).mapNotNull { index ->
                    entries.get(index).takeUnless { it.isNull }?.id
                }
                if (definedIds.size == entries.size()) return@forEach

                val pairs = entries.offsetReferenceList
                pairs.clearChildes()
                pairs.setSize(definedIds.size)
                definedIds.forEachIndexed { index, id -> pairs.get(index).idx = id }
                entries.removeIf { it.isNull }
            }
        }
    }

    /**
     * Adds a new or changed file resource to the archive, in place of the original entry. A new
     * file also becomes the value of its entry in its configuration.
     */
    private fun addResFile(packageBlock: PackageBlock, file: File) {
        val archiveName = archiveNameOf(file)

        if (isNewFile(file)) {
            val type = EncodeUtil.getTypeNameFromResFile(file)
            val name = EncodeUtil.getEntryNameFromResFile(file)
            val resourceEntry = packageBlock.tableBlock.getLocalResource(packageBlock, type, name)
                ?: throw PatchException("Local resource not defined: @$type/$name, for path: $archiveName")
            resourceEntry.getOrCreate(EncodeUtil.getQualifiersFromResFile(file)).setValueAsString(archiveName)
        }

        val source = if (file.extension == "xml") {
            XMLEncodeSource(packageBlock, XMLFileParserSource(archiveName, file))
        } else {
            FileInputSource(file, archiveName).also { it.method = Archive.STORED }
        }
        module.add(source)
        encodedEntries += archiveName
    }

    /**
     * Adds a compiled resource file a patch changed in its binary form, in place of the original entry,
     * compressed as the input APK compresses it.
     */
    private fun addHeldFile(packageBlock: PackageBlock, state: BinaryXmlState) {
        val method = module.zipEntryMap.getInputSource(state.archiveName)?.method ?: Archive.DEFLATED
        module.add(ByteInputSource(state.encode(packageBlock), state.archiveName).also { it.method = method })
        encodedEntries += state.archiveName
    }

    /**
     * Encodes what patches changed of a values file as a document: the resources they added or changed
     * replace those of the table, and the resources they removed are emptied, unless a file under `res/`
     * defines them.
     *
     * @return How many entries were encoded, and how many the edits declare.
     */
    private fun encodeValuesEdits(
        edits: ValuesEdits,
        typeBlock: TypeBlock,
        valuesCoder: XmlCoder.ValuesXml,
        isDefinedByFile: (Entry) -> Boolean,
    ): Pair<Int, Int> {
        val existing = typeBlock.listEntries(true).associateByTo(HashMap()) { it.name }
        var encoded = 0
        val elements = edits.encodableElements()
        elements.forEach { element ->
            if (encodeElement(element, typeBlock, valuesCoder, existing) != null) encoded++
        }
        edits.removed.forEach { name ->
            existing[name]?.let { if (!isDefinedByFile(it)) it.empty() }
        }
        return encoded to elements.size
    }

    /**
     * Encodes a resource of a values file into its entry, unless the entry already holds the string it declares.
     *
     * @param existing The entries of the configuration, by name.
     * @return The entry if it was encoded, or null if it already held the value.
     */
    private fun encodeElement(
        element: XMLElement,
        typeBlock: TypeBlock,
        valuesCoder: XmlCoder.ValuesXml,
        existing: Map<String?, Entry>,
    ): Entry? {
        val name = element.getAttributeValue("name")
        if (existing[name]?.takeIf { it.holdsString(element) } != null) return null

        val entry = typeBlock.getOrCreateDefinedEntry(name)
            ?: throw XmlEncodeException("Undefined entry name: " + element.debugText)
        // Encoding a bag over an existing one appends its children instead.
        if (entry.isComplex) entry.empty()
        valuesCoder.encodeEntry(element, typeBlock)
        entry.restoreArchiveName()
        return entry
    }

    /**
     * Encodes a values file over the entries of its type in its configuration. A string the file
     * declares as the table already holds it is left alone, which is most of a strings file a
     * patch added a few strings to. An entry the file no longer declares is emptied, unless a
     * file under `res/` defines it or the file is an addition.
     *
     * @param addition Whether the file only adds or replaces entries, see [isAddition].
     * @return How many entries were encoded, and how many the file declares.
     */
    private fun encodeValuesFile(
        valuesFile: File,
        typeBlock: TypeBlock,
        valuesCoder: XmlCoder.ValuesXml,
        isDefinedByFile: (Entry) -> Boolean,
        addition: Boolean,
    ): Pair<Int, Int> {
        val parser = XMLFactory.newPullParser(valuesFile)
        if (parser.eventType == XmlPullParser.START_DOCUMENT) parser.next()
        if (XMLUtil.ensureStartTag(parser) != XmlPullParser.START_TAG) {
            throw XmlEncodeException(parser, "Expecting xml state START_TAG")
        }
        if (parser.name == PackageBlock.TAG_resources) parser.next()

        val existing = typeBlock.listEntries(true).associateByTo(HashMap()) { it.name }
        val declared = HashSet<Entry>()
        val encoded = HashSet<Entry>()
        try {
            while (XMLUtil.ensureStartTag(parser) == XmlPullParser.START_TAG) {
                val element = XMLElement.parseElement(parser)
                val entry = encodeElement(element, typeBlock, valuesCoder, existing)
                if (entry == null) {
                    declared += existing.getValue(element.getAttributeValue("name"))
                } else {
                    declared += entry
                    encoded += entry
                }
            }
        } catch (exception: XmlEncodeException) {
            throw XmlEncodeException(parser, exception.message)
        } finally {
            IOUtil.close(parser)
        }

        if (!addition) {
            existing.values.forEach { entry ->
                if (entry !in declared && !isDefinedByFile(entry)) entry.empty()
            }
        }

        return encoded.size to declared.size
    }

    /**
     * Whether the entry already holds the plain string the element declares, as encoding the
     * element would set it. Any doubt is answered by encoding, so this only ever saves work.
     */
    private fun Entry.holdsString(element: XMLElement): Boolean {
        if (element.name != "string" || element.hasChildElements() || element.getAttributeValue("type") != null) {
            return false
        }
        if (isNull || isComplex) return false
        val value = resValue ?: return false
        if (value.valueType != ValueType.STRING) return false

        val text = element.textContent
        // A reference or a coded value is encoded as such, never as a plain string.
        if (text.isEmpty() || text[0] == '@' || text[0] == '?') return false
        val current = value.valueAsString ?: return false

        // Text without an escape or quoting encodes to itself.
        return (text == current && text[0] != '"' && text.indexOf('\\') < 0) ||
            current == XmlSanitizer.unEscapeUnQuote(StyleDocument.copyInner(element).getXml(false))
    }

    /**
     * An entry that shares a file with another resource is decoded as the path of that file, and
     * the path is its decoded alias. The table must name the archive entry the file really is.
     */
    private fun Entry.restoreArchiveName() {
        val value = resValue ?: return
        if (value.valueType != ValueType.STRING) return
        val path = value.valueAsString ?: return
        if (!path.startsWith("res/")) return
        originalNameOf(path)?.let { if (it != path) setValueAsString(it) }
    }

    /**
     * Empties the entry while keeping its name, so the name still resolves to its id and the
     * resource stays undefined in this configuration.
     */
    private fun Entry.empty() {
        val name = name
        isNull = true
        // An emptied entry drops its name with its value; hold it so the name still resolves.
        if (name != null) setName(name, true)
    }
}

/**
 * Whether the app may use sparse entry tables, which cannot be read below Android 8.
 */
/**
 * Whether a configuration is one a patch added, told apart by a mobile country or network code no device reports.
 */
internal fun isPatchedConfiguration(qualifiers: String): Boolean {
    val config = ResConfig.parse(qualifiers)
    return config.mcc in PATCH_MOBILE_CODES || config.mnc in PATCH_MOBILE_CODES
}

internal fun ApkModule.useSparseEntries(): Boolean {
    val minSdk = androidManifest.minSdkVersion
    return minSdk != null && minSdk >= SPARSE_ENTRIES_MIN_SDK
}

/**
 * The block a configuration of a patch is encoded into.
 *
 * A configuration is given a dense entry table the moment the encoder creates it, sized to the
 * largest configuration of its type: an offset for every resource of the type, whether this
 * configuration defines it or not. A patch can add more than a thousand configurations to select
 * a color with, each defining a handful of resources out of thousands, so one created here
 * carries a sparse offset table instead, listing only the resources it defines. It is set while
 * the configuration is still empty, so no entry table is ever built for the resources it leaves
 * out. Below Android 8 the resource system cannot read a sparse table, so those apps keep the
 * dense one and pay for it in memory.
 */
internal fun PackageBlock.patchedTypeBlock(valuesFile: File, useSparseEntries: Boolean): TypeBlock {
    val resConfig = ResConfig.parse(XmlEncodeUtil.getQualifiersFromValuesXml(valuesFile))
    val specTypePair = getOrCreateSpecTypePair(XmlEncodeUtil.getTypeFromValuesXml(valuesFile))
    val denseEntryCount = specTypePair.highestEntryCount

    return specTypePair.getTypeBlock(resConfig)
        ?: specTypePair.getOrCreateTypeBlock(resConfig).also {
            if (useSparseEntries) {
                it.headerBlock.isSparse = true
            } else {
                it.ensureEntriesCount(denseEntryCount)
            }
        }
}
