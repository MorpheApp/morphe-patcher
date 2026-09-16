/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.patch.PatchException
import com.reandroid.apk.ApkModule
import com.reandroid.apk.UncompressedFiles
import com.reandroid.apk.xmlencoder.EncodeUtil
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.archive.Archive
import com.reandroid.archive.FileInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.arsc.coder.xml.XmlEncodeException
import com.reandroid.arsc.coder.xml.XmlEncodeUtil
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ValueType
import com.reandroid.utils.io.IOUtil
import com.reandroid.xml.StyleDocument
import com.reandroid.xml.XMLElement
import com.reandroid.xml.XMLFactory
import com.reandroid.xml.XMLUtil
import com.reandroid.arsc.coder.XmlSanitizer
import com.reandroid.xml.source.XMLFileParserSource
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.logging.Logger

/**
 * Encodes the resources a patch changed into the resource table of the input APK, instead of
 * rebuilding that table from every decoded values file.
 *
 * The table an app ships is the authority for everything a patch left alone. Only the values
 * files a patch touched are re-encoded, each replacing the entries of its type in its
 * configuration, and only new or changed files under `res/` are added to the archive. The rest
 * of the archive is the input APK, which [module] already holds.
 *
 * @param module The input APK, loaded with its framework attached. It becomes the output.
 * @param workingDir The decoded working directory.
 * @param packageDirectories Original package name to the directory it was decoded to.
 * @param archiveNameOf Maps a decoded `res/` path to the name of the entry in the archive.
 * @param isNewFile Whether a decoded file did not exist at decode time.
 */
internal class IncrementalResourceEncoder(
    private val module: ApkModule,
    private val workingDir: File,
    private val packageDirectories: Map<String, File>,
    private val archiveNameOf: (String) -> String,
    private val isNewFile: (File) -> Boolean,
) {
    private val logger = Logger.getLogger(IncrementalResourceEncoder::class.java.name)

    /** Archive entry names this encoder added or replaced. */
    val encodedEntries = mutableSetOf<String>()

    private var encodedValues = 0
    private var declaredValues = 0
    private var parseNanos = 0L
    private var checkNanos = 0L
    private var encodeNanos = 0L

    /**
     * @param modifiedResources Decoded `res/` files a patch added or changed.
     * @param deletedEntries Archive entry names of resource files a patch deleted.
     * @param patchedConfigurations Values directories of resource configurations patches added,
     * which are encoded sparse.
     * @param useSparseEntries Whether a configuration of a patch may use a sparse entry table.
     * @param publicIds Every resource id of the package as `public.xml` now declares them.
     * @param originalPackageName The package name of the app as decoded.
     * @param newPackageName The package name the manifest now declares.
     */
    fun encode(
        modifiedResources: Set<File>,
        deletedEntries: Set<String>,
        patchedConfigurations: List<File>,
        useSparseEntries: Boolean,
        publicIds: Map<Pair<String, String>, Int>,
        originalPackageName: String,
        newPackageName: String,
    ) {
        val timings = LinkedHashMap<String, Long>()
        fun <T> timed(stage: String, block: () -> T): T {
            val start = System.nanoTime()
            return block().also { timings[stage] = (timings[stage] ?: 0L) + (System.nanoTime() - start) / 1_000_000 }
        }

        val tableBlock = timed("table") { module.tableBlock } ?: throw PatchException("The APK has no resource table")
        val valuesCoder = XmlCoder.getInstance().VALUES_XML

        val uncompressedFiles = workingDir.resolve(UncompressedFiles.JSON_FILE)
        if (uncompressedFiles.isFile) module.uncompressedFiles.fromJson(uncompressedFiles)

        var mainPackage: PackageBlock? = null

        packageDirectories.forEach { (packageName, packageDirectory) ->
            val resDirectory = packageDirectory.resolve("res")
            val publicXml = resDirectory.resolve("values/public.xml")
            if (!publicXml.isFile) return@forEach

            val packageBlock = tableBlock.firstOrNull { it.name == packageName }
                ?: tableBlock.pickOne()
                ?: throw PatchException("No resource package for $packageName in the table")
            if (mainPackage == null) mainPackage = packageBlock
            packageBlock.setTag(publicXml)

            // Registers the ids the processors allocated: an empty entry to encode into, for each
            // resource the table does not have yet.
            timed("ids") { packageBlock.registerIds(publicIds) }
            if (packageName == originalPackageName && packageBlock.name != newPackageName) {
                packageBlock.name = newPackageName
            }

            val modified = modifiedResources.filter { it.isFile && it.startsWith(resDirectory) }
            val (valuesFiles, resFiles) = modified.partition { it.parentFile.isValuesDirectory() }

            val overlayable = resDirectory.resolve("values/overlayable.xml")
            val patchedDirectories = patchedConfigurations.toSet()
            val appValuesFiles = valuesFiles.filter {
                it.extension == "xml" &&
                        it.name != "public.xml" &&
                        it != overlayable &&
                        it.parentFile !in patchedDirectories
            }

            // Attributes first: styles and enums are encoded against attribute names.
            val (attrs, values) = appValuesFiles.partition { it.name == "attrs.xml" }
            timed("values") {
                (attrs + values).forEach { valuesFile ->
                    val typeBlock = packageBlock.getOrCreateTypeBlock(
                        XmlEncodeUtil.getQualifiersFromValuesXml(valuesFile),
                        XmlEncodeUtil.getTypeFromValuesXml(valuesFile),
                    )
                    encodeValuesFile(valuesFile, typeBlock, valuesCoder)
                }
            }

            if (overlayable in modifiedResources && overlayable.isFile) {
                packageBlock.overlayableList.clearChildes()
                packageBlock.overlayableList.parse(XMLFactory.newPullParser(overlayable))
            }

            patchedConfigurations.filter { it.startsWith(resDirectory) }.forEach { valuesDirectory ->
                valuesDirectory.listFiles { file: File -> file.isFile && file.extension == "xml" }
                    .orEmpty().forEach { valuesFile ->
                        val typeBlock = packageBlock.patchedTypeBlock(valuesFile, useSparseEntries)
                        valuesCoder.encode(XMLFactory.newPullParser(valuesFile), typeBlock)
                    }
            }

            timed("files") { resFiles.forEach { file -> addResFile(packageBlock, resDirectory, file) } }

            logger.info(
                "Encoded $encodedValues of $declaredValues values in ${appValuesFiles.size} files, " +
                        "${resFiles.size} resource files and ${patchedConfigurations.size} " +
                        "configurations of patches for $packageName"
            )

            timed("refresh") { packageBlock.sortTypes() }
        }

        deletedEntries.filter { it.startsWith("res/") }.forEach { entryName ->
            module.listReferencedEntries(entryName).forEach { it.setNull(true) }
        }

        val manifest = workingDir.resolve("AndroidManifest.xml")
        if (manifest.isFile) {
            val packageBlock = mainPackage ?: tableBlock.pickOne()
            module.add(
                XMLEncodeSource(packageBlock, XMLFileParserSource("AndroidManifest.xml", manifest)).also {
                    it.method = Archive.STORED
                    it.sort = 0
                }
            )
            encodedEntries += "AndroidManifest.xml"
        }

        timed("refresh") { tableBlock.refresh() }
        encodedEntries += "resources.arsc"

        logger.fine {
            "Resource table update timings: " + timings.entries.joinToString { "${it.key}=${it.value}ms" } +
                    " (values: parse=${parseNanos / 1_000_000}ms check=${checkNanos / 1_000_000}ms " +
                    "encode=${encodeNanos / 1_000_000}ms)"
        }
    }

    /**
     * Creates an empty, named entry for every id the table does not define, so the values file
     * or resource file that declares the resource has an entry to encode into. An id resource
     * has no file to come from, so it is given its value here.
     */
    private fun PackageBlock.registerIds(publicIds: Map<Pair<String, String>, Int>) {
        var registered = 0
        publicIds.forEach { (typeAndName, resourceId) ->
            if ((resourceId ushr 24) != id) return@forEach
            if (getResource(resourceId) != null) return@forEach

            val (type, name) = typeAndName
            val typeId = (resourceId shr 16) and 0xff
            getOrCreateTypeString(typeId, type)
            val entry = getOrCreateTypeBlock(typeId.toByte(), "").getOrCreateEntry(resourceId and 0xffff)
            entry.setName(name, true)
            if (type == "id") {
                entry.setValueAsBoolean(false)
                entry.header.isPublic = true
                entry.header.isWeak = true
            }
            registered++
        }
        logger.fine { "Registered $registered new resource ids in $name" }
    }

    /**
     * Adds a new or changed file resource to the archive, in place of the original entry. A new
     * file also becomes the value of its entry in its configuration.
     */
    private fun addResFile(packageBlock: PackageBlock, resDirectory: File, file: File) {
        val alias = "res/" + file.relativeTo(resDirectory).invariantSeparatorsPath
        val archiveName = archiveNameOf(alias)

        if (isNewFile(file)) {
            val type = EncodeUtil.getTypeNameFromResFile(file)
            val name = EncodeUtil.getEntryNameFromResFile(file)
            val resourceEntry = packageBlock.tableBlock.getLocalResource(packageBlock, type, name)
                ?: throw PatchException("Local resource not defined: @$type/$name, for path: $alias")
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
     * Encodes a values file over the entries of its type in its configuration. A string the file
     * declares as the table already holds it is left alone, which is most of a strings file a
     * patch added a few strings to. An entry the file no longer declares is emptied.
     */
    private fun encodeValuesFile(valuesFile: File, typeBlock: TypeBlock, valuesCoder: XmlCoder.ValuesXml) {
        val parser = XMLFactory.newPullParser(valuesFile)
        if (parser.eventType == XmlPullParser.START_DOCUMENT) parser.next()
        if (XMLUtil.ensureStartTag(parser) != XmlPullParser.START_TAG) {
            throw XmlEncodeException(parser, "Expecting xml state START_TAG")
        }
        if (parser.name == PackageBlock.TAG_resources) parser.next()

        val existing = typeBlock.listEntries(true).associateByTo(HashMap()) { it.name }
        val declared = HashSet<Entry>()
        var encoded = 0
        try {
            while (XMLUtil.ensureStartTag(parser) == XmlPullParser.START_TAG) {
                var start = System.nanoTime()
                val element = XMLElement.parseElement(parser)
                parseNanos += System.nanoTime() - start
                start = System.nanoTime()

                val name = element.getAttributeValue("name")
                val unchanged = existing[name]?.takeIf { it.holdsString(element) }
                if (unchanged != null) {
                    declared += unchanged
                    checkNanos += System.nanoTime() - start
                    continue
                }
                checkNanos += System.nanoTime() - start
                start = System.nanoTime()

                val entry = typeBlock.getOrCreateDefinedEntry(name)
                    ?: throw XmlEncodeException("Undefined entry name: " + element.debugText)
                declared += entry
                // Encoding a bag over an existing one appends its children instead.
                if (entry.isComplex) entry.empty()
                valuesCoder.encodeEntry(element, typeBlock)
                encoded++
                encodeNanos += System.nanoTime() - start
            }
        } catch (exception: XmlEncodeException) {
            throw XmlEncodeException(parser, exception.message)
        } finally {
            IOUtil.close(parser)
        }

        // A resource the file no longer declares is gone from this configuration. A resource
        // that a file under res/ defines was never in the values file to begin with.
        typeBlock.listEntries(true).forEach { entry ->
            if (entry !in declared && !entry.isDefinedByFile()) entry.empty()
        }

        encodedValues += encoded
        declaredValues += declared.size
    }

    /**
     * Whether the entry already holds the plain string the element declares, as encoding the
     * element would set it.
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
        if (text == current && text[0] != '"' && text.indexOf('\\') < 0) return true
        return current == XmlSanitizer.unEscapeUnQuote(StyleDocument.copyInner(element).getXml(false))
    }

    private fun Entry.isDefinedByFile(): Boolean {
        if (isComplex || !TypeBlock.canHaveResourceFile(typeName)) return false
        val value = resValue ?: return false
        return value.valueType == ValueType.STRING && value.valueAsString?.startsWith("res/") == true
    }

    /**
     * Empties the entry while keeping its name, so the name still resolves to its id and the
     * resource stays undefined in this configuration.
     */
    private fun Entry.empty() {
        val name = name
        setNull(true)
        // An emptied entry drops its name with its value; hold it so the name still resolves.
        if (name != null) setName(name, true)
    }

    /**
     * The block a configuration of a patch is encoded into: a sparse one when created here, so
     * no entry table is built for the thousands of resources it leaves out.
     */
    private fun PackageBlock.patchedTypeBlock(valuesFile: File, useSparseEntries: Boolean): TypeBlock {
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

    private fun File.isValuesDirectory() = name == "values" || name.startsWith("values-")
}
