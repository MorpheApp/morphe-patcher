/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.PathMap
import app.morphe.patcher.resource.PathPatterns
import app.morphe.patcher.resource.writeJson
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ResFile
import com.reandroid.apk.UncompressedFiles
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.coder.CoderSetting
import com.reandroid.arsc.coder.xml.ValuesDirectorySerializer
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.arsc.container.SpecTypePair
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.utils.io.IOUtil
import com.reandroid.xml.XMLFactory
import com.reandroid.xml.XmlIndentingSerializer
import com.reandroid.xml.XmlSerializerWrapper
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.OutputStream
import java.util.function.Predicate

internal const val PUBLIC_XML = "res/values/public.xml"
internal const val OVERLAYABLE_XML = "res/values/overlayable.xml"

private val DEX_FILE_NAME = Regex("classes\\d*\\.dex")

/**
 * Writes which entries of the archive are stored uncompressed, as the decoder of every resource does.
 */
internal fun ApkModule.writeUncompressedFiles(workingDir: File) {
    val uncompressedFiles = UncompressedFiles().apply {
        addCommonExtensions()
        addPath(zipEntryMap)
    }
    workingDir.resolve(UncompressedFiles.JSON_FILE).writeJson(uncompressedFiles.toJson())
}

/**
 * The resources of a package of the resource table, by the paths they decode to relative to [directory].
 *
 * @param name The name of the package.
 * @param id The id of the package.
 * @param directory The directory the package decodes to.
 */
internal class DecodablePackage(val name: String, val id: Int, val directory: File) {
    /** The resource files of the package, mapped to the name of the archive entry they decode from. */
    internal val resourceFiles = HashMap<String, String>()

    /** The values files of the package, mapped to the type and configuration they hold. */
    internal val valuesFiles = HashMap<String, ValuesFile>()

    internal var hasOverlayables = false

    /**
     * Every path of the package, including the values files the resource table is decoded to.
     */
    val paths: Sequence<String>
        get() = resourceFiles.keys.asSequence() + valuesFiles.keys.asSequence() + PUBLIC_XML +
                (if (hasOverlayables) sequenceOf(OVERLAYABLE_XML) else emptySequence())

    /** [paths] by the directory they are in. The catalog is complete before anything searches it. */
    private val pathsByDirectory by lazy { paths.groupBy { it.substringBeforeLast('/', "") } }

    /**
     * The paths that match [matcher]. Each directory is settled once, and only the paths in a directory
     * a match can be in are tested, since a large app has thousands of paths and patches decode a few at a time.
     */
    fun matchingPaths(matcher: PathPatterns): Sequence<String> =
        pathsByDirectory.asSequence().flatMap { (directory, paths) ->
            paths.asSequence().filter(matcher.matcherIn(directory))
        }

    operator fun contains(path: String) =
        path in resourceFiles || path in valuesFiles || path == PUBLIC_XML || (hasOverlayables && path == OVERLAYABLE_XML)

    /**
     * Whether the file at [path] is generated from the entries of the resource table rather than from an
     * archive entry, so that content a patch wrote there can be merged with it.
     */
    fun isValuesFile(path: String) = path in valuesFiles || path == PUBLIC_XML || path == OVERLAYABLE_XML

    /**
     * @param type The name of the resource type, such as `string`.
     * @param qualifiers The qualifiers of the configuration, such as `-de`, or empty for the default.
     */
    internal class ValuesFile(val type: String, val qualifiers: String)
}

/**
 * Decodes the files of an APK when patches request them, instead of all of them before patches execute.
 * Each file is decoded to the same path and with the same content as [com.reandroid.apk.ApkModuleXmlDecoder]
 * decodes it to.
 *
 * What can be decoded is cataloged from the resource table when this is created, which reads the table
 * but decodes nothing.
 *
 * @param workingDir The working directory to decode to.
 * @param module The input APK with its parsed resource table. It may be released and loaded again between
 * requests, which must give a table with the same resource names.
 */
internal class OnRequestDecoder(
    private val workingDir: File,
    private val module: () -> ApkModule,
) {
    /** The packages of the resource table by their name. */
    val packages: Map<String, DecodablePackage>

    /**
     * The entries of the archive other than the manifest, the resource table, resource files, DEX files and
     * native libraries. They are extracted rather than decoded.
     */
    val rootEntries: Set<String>

    /** Archive entry names of the resource files of the resource table. */
    val resourceArchiveNames: Set<String>

    val pathMap: PathMap

    /**
     * The coder setting values are decoded with. The encoder installs its own on the shared instance, which
     * must not change how a file decoded afterward reads.
     */
    private val decodeSetting: CoderSetting = XmlCoder.getInstance().setting

    /** Dropped when the module is released, so that it does not keep the module in memory. */
    private var state: ModuleState? = ModuleState(module())

    /**
     * The entries decoded as files rather than into values, by resource id to the qualifiers of their
     * configurations. Several resources can name the same file, and which of them it is decoded for is
     * up to the order the table lists them in, so it is decided once and kept when the table is loaded
     * again.
     */
    private val fileEntries = HashMap<Int, HashSet<String>>()

    private val isFileEntry = Predicate<Entry> { entry ->
        fileEntries[entry.resourceId]?.contains(entry.resConfig.qualifiers) == true
    }

    init {
        val state = currentState()
        val tableBlock = state.module.tableBlock
        packages = tableBlock.listPackages().associate { packageBlock ->
            packageBlock.name to DecodablePackage(
                packageBlock.name,
                packageBlock.id,
                workingDir.resolve(TableBlock.DIRECTORY_NAME).resolve(packageBlock.buildDecodeDirectoryName()),
            ).also { it.hasOverlayables = !packageBlock.overlayableList.isEmpty }
        }

        val pathMapEntries = mutableListOf<Pair<String, String>>()
        state.resourceFiles.forEach { (archiveName, resFile) ->
            val entry = resFile.pickOne()
            if (!entry.isNull) fileEntries.getOrPut(entry.resourceId) { HashSet() } += entry.resConfig.qualifiers
            val alias = resFile.buildPath(PackageBlock.RES_DIRECTORY_NAME)
            packages[entry.packageBlock.name]?.resourceFiles?.put(alias, archiveName)
            pathMapEntries += archiveName to alias
        }
        pathMap = PathMap(pathMapEntries)
        resourceArchiveNames = state.resourceFiles.keys.toHashSet()

        tableBlock.listPackages().forEach { packageBlock ->
            val decodablePackage = packages.getValue(packageBlock.name)
            packageBlock.listSpecTypePairs().forEach { specTypePair ->
                specTypePair.listResConfig().forEach { resConfig ->
                    if (specTypePair.hasValues(resConfig, isFileEntry)) {
                        val qualifiers = resConfig.qualifiers
                        decodablePackage.valuesFiles[valuesPath(specTypePair.typeName, qualifiers)] =
                            DecodablePackage.ValuesFile(specTypePair.typeName, qualifiers)
                    }
                }
            }
        }

        rootEntries = state.module.inputSources.mapNotNullTo(HashSet()) { source ->
            source.alias.takeIf { name ->
                !name.endsWith("/") &&
                        name != AndroidManifestBlock.FILE_NAME &&
                        name != TableBlock.FILE_NAME &&
                        name !in resourceArchiveNames &&
                        // Collides with the paths of the decoded resources, which take precedence.
                        !name.startsWith("${PackageBlock.RES_DIRECTORY_NAME}/") &&
                        !name.startsWith("lib/") &&
                        !DEX_FILE_NAME.matches(name)
            }
        }
    }

    /** How many files can be decoded or extracted, the manifest included. */
    val size get() = packages.values.sumOf { it.paths.count() } + rootEntries.size + 1

    /**
     * The resource files of a module as it was loaded, by the name of the archive entry they decode from.
     */
    private class ModuleState(val module: ApkModule) {
        val resourceFiles = LinkedHashMap<String, ResFile>()

        init {
            module.listResFiles().forEach { resFile ->
                // As the decoder of every resource does, a file named as XML that is not binary XML is
                // not decoded, and the entries naming it stay in the values.
                if (!resFile.isBinaryXml && resFile.filePath.endsWith(".xml")) return@forEach
                if (resFile.pickOne() == null) return@forEach

                resourceFiles[resFile.filePath] = resFile
            }
        }
    }

    private fun currentState(): ModuleState {
        val current = module()
        return state?.takeIf { it.module === current } ?: ModuleState(current).also { state = it }
    }

    /**
     * Forgets the module, which is being released. Decoding loads it again.
     */
    fun releaseModule() {
        state = null
    }

    private fun PackageBlock.matches(decodablePackage: DecodablePackage) =
        name == decodablePackage.name && id == decodablePackage.id

    private fun ApkModule.packageBlock(decodablePackage: DecodablePackage): PackageBlock =
        tableBlock.listPackages().firstOrNull { it.matches(decodablePackage) }
            ?: throw PatchException("Package ${decodablePackage.name} not found in the resource table")

    /**
     * Writes the `package.json` of every package, which tells packages apart in the working directory.
     */
    fun writePackageInfo() {
        val tableBlock = currentState().module.tableBlock
        packages.values.forEach { decodablePackage ->
            val packageBlock = tableBlock.listPackages().first { it.matches(decodablePackage) }
            val file = decodablePackage.directory.resolve(PackageBlock.JSON_FILE_NAME)
            file.writeJson(packageBlock.toJson(false))
        }
    }

    /**
     * Writes which entries of the archive are stored uncompressed.
     */
    fun writeUncompressedFiles() = currentState().module.writeUncompressedFiles(workingDir)

    /**
     * Decodes the manifest to `AndroidManifest.xml` in the working directory.
     */
    fun decodeManifest() = withDecodeSetting {
        val module = currentState().module
        val manifestBlock = module.androidManifest
        val packageBlock = manifestBlock.packageBlock ?: module.tableBlock.let { tableBlock ->
            tableBlock.pickOne(manifestBlock.guessCurrentPackageId()) ?: tableBlock.pickOne()
        }
        if (packageBlock != null && manifestBlock.packageBlock == null) manifestBlock.setPackageBlock(packageBlock)

        workingDir.resolve(AndroidManifestBlock.FILE_NAME).createOutputStream().use { output ->
            val serializer = XMLFactory.newSerializer(output, manifestBlock.encoding)
            manifestBlock.serialize(serializer)
            IOUtil.close(serializer)
        }
    }

    /**
     * Decodes files of a package, overwriting whatever is at their paths.
     *
     * @param paths Paths of the package, as [DecodablePackage.paths] lists them.
     * @return The files that were written, which leaves out a values file that has no values to decode.
     */
    fun decode(decodablePackage: DecodablePackage, paths: Collection<String>): List<File> = withDecodeSetting {
        val state = currentState()
        val packageBlock = state.module.packageBlock(decodablePackage)

        paths.mapNotNull { path ->
            val file = decodablePackage.directory.resolve(path)
            try {
                when {
                    path == PUBLIC_XML -> decodePublicXml(packageBlock, file)
                    path == OVERLAYABLE_XML -> decodeOverlayables(packageBlock, file)
                    path in decodablePackage.valuesFiles ->
                        decodeValues(packageBlock, decodablePackage.valuesFiles.getValue(path), file)

                    path in decodablePackage.resourceFiles ->
                        decodeResourceFile(state, decodablePackage.resourceFiles.getValue(path), file)

                    else -> throw PatchException("$path is not a resource of ${decodablePackage.name}")
                }
            } catch (exception: PatchException) {
                throw exception
            } catch (exception: Exception) {
                throw PatchException("Failed to decode $path", exception)
            }
            file.takeIf { it.isFile }
        }
    }

    private fun decodePublicXml(packageBlock: PackageBlock, file: File) {
        file.parentFile.mkdirs()
        packageBlock.serializePublicXml(file)
    }

    private fun decodeOverlayables(packageBlock: PackageBlock, file: File) {
        file.parentFile.mkdirs()
        val serializer = XmlIndentingSerializer(XMLFactory.newSerializer(file))
        XMLFactory.setEnableIndentAttributes(serializer, false)
        packageBlock.overlayableList.serialize(serializer)
    }

    private fun decodeValues(
        packageBlock: PackageBlock,
        valuesFile: DecodablePackage.ValuesFile,
        file: File,
    ) {
        val specTypePair = packageBlock.getSpecTypePair(valuesFile.type)
            ?: throw PatchException("No resources of type ${valuesFile.type} in ${packageBlock.name}")
        val resConfig = specTypePair.listResConfig().firstOrNull { it.qualifiers == valuesFile.qualifiers }
            ?: throw PatchException("No ${valuesFile.type} resources for \"${valuesFile.qualifiers}\" in ${packageBlock.name}")
        val typeBlock = specTypePair.getTypeBlock(resConfig)

        // Writes where the decoder of every resource writes the file, and deletes it again if empty.
        val serializers = ValuesDirectorySerializer(workingDir.resolve(TableBlock.DIRECTORY_NAME))
        val serializer = serializers.createSerializer(typeBlock)
        val decoded = XmlCoder.getInstance().VALUES_XML.decode(
            ArchiveNameAliasingSerializer(serializer),
            specTypePair,
            resConfig,
            isFileEntry,
        )
        serializers.onFinish(serializer, decoded)

        check(file.isFile || decoded == 0) { "Values of ${valuesFile.type} were not decoded to $file" }
    }

    private fun decodeResourceFile(state: ModuleState, archiveName: String, file: File) {
        val resFile = state.resourceFiles[archiveName]
            ?: throw PatchException("$archiveName is not a resource file")
        val source = resFile.inputSource

        file.createOutputStream().use { output ->
            if (resFile.isBinaryXml) {
                val document = ResXmlDocument()
                source.openStream().use { document.readBytes(it) }
                document.setPackageBlock(resFile.pickOne().packageBlock)

                val serializer = XMLFactory.newSerializer(output, document.encoding)
                document.serialize(serializer)
                IOUtil.close(serializer)
            } else {
                source.openStream().use { it.copyTo(output) }
            }
        }
    }

    /**
     * The decoder of every resource renames each resource file to the path it decodes it to, so a value
     * naming a file another resource also uses reads as that path. Values are decoded from the table as
     * the input APK holds it here, so the same renaming is applied as they are written.
     */
    private inner class ArchiveNameAliasingSerializer(serializer: XmlSerializer) : XmlSerializerWrapper(serializer) {
        override fun text(text: String): XmlSerializer {
            val alias = text.takeIf { it in resourceArchiveNames }?.let(pathMap::getAlias)
            return super.text(alias ?: text)
        }
    }

    private inline fun <T> withDecodeSetting(block: () -> T): T {
        val coder = XmlCoder.getInstance()
        val setting = coder.setting
        coder.setting = decodeSetting
        try {
            return block()
        } finally {
            coder.setting = setting
        }
    }

    private fun File.createOutputStream(): OutputStream {
        parentFile?.mkdirs()
        return outputStream().buffered()
    }

    private companion object {
        fun valuesPath(type: String, qualifiers: String): String {
            val fileName = if (type.endsWith("s")) type else "${type}s"
            return "${PackageBlock.RES_DIRECTORY_NAME}/${PackageBlock.VALUES_DIRECTORY_NAME}$qualifiers/$fileName.xml"
        }

        /**
         * Whether the configuration has an entry that is decoded into its values file, as
         * [XmlCoder.ValuesXml.decode] decides it.
         */
        fun SpecTypePair.hasValues(resConfig: ResConfig, isFileEntry: Predicate<Entry>): Boolean {
            val iterator = getResources()
            while (iterator.hasNext()) {
                val entry = iterator.next().get(resConfig) ?: continue
                if ((entry.isComplex || !entry.isNull) && !isFileEntry.test(entry)) return true
            }
            return false
        }
    }
}
