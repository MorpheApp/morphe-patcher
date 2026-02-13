/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.patch

import app.morphe.patcher.InternalApi
import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.util.Document
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import org.w3c.dom.Element
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.logging.Logger


/**
 * A context for patches containing the current state of resources.
 *
 * @param packageMetadata The [PackageMetadata] of the target apk.
 */
class ResourcePatchContext internal constructor(
    val packageMetadata: PackageMetadata,
    private val config: PatcherConfig,
) : PatchContext<PatcherResult.PatchedResources?> {
    private val logger = Logger.getLogger(ResourcePatchContext::class.java.name)

    /**
     * Read a document from an [InputStream].
     */
    fun document(inputStream: InputStream) = Document(inputStream)

    /**
     * Read and write documents in the [PatcherConfig.apkFiles].
     */
    fun document(path: String) = Document(get(path))

    /**
     * Set of resources from [PatcherConfig.apkFiles] to delete.
     */
    private val deleteResources = mutableSetOf<String>()

    /**
     * Decode resources of [PatcherConfig.apkFile].
     *
     * @param mode The [ResourceMode] to use.
     */
    internal fun decodeResources(mode: ResourceMode) =
        with(packageMetadata.apkInfo) {
            config.initializeTemporaryFilesDirectories()

            val xmlDecoder = ApkModuleXmlDecoder(this)
            xmlDecoder.setKeepResPath(false)
            if (mode == ResourceMode.FULL) {
                logger.info("Decoding resources")

                xmlDecoder.decode(config.apkFiles)

                // Delete all the dex files so they don't get built into the final resources.apk.
                config.apkFiles.resolve("dex").deleteRecursively()
            } else {
                logger.info("Decoding app manifest")

                xmlDecoder.decodeAndroidManifest(config.apkFiles)
            }

            val manifest = this.androidManifest
            packageMetadata.let { metadata ->
                metadata.packageName = manifest.packageName
                metadata.packageVersion = manifest.versionName ?: manifest.versionCode.toString()
            }
        }

    /**
     * Compile resources in [PatcherConfig.apkFiles].
     *
     * @return The [PatcherResult.PatchedResources].
     */
    @InternalApi
    override fun get(): PatcherResult.PatchedResources? {
        if (config.resourceMode == ResourceMode.NONE) return null

        logger.info("Compiling modified resources")

        val resources = config.patchedFiles.resolve("resources").also { it.mkdirs() }

        val resourcesApkFile =
            if (config.resourceMode == ResourceMode.FULL) {
                resources.resolve("resources.apk").apply {
                    // FIXME: Update package.json and public.xml here with new package name.
                    // Unsure how to handle multiple resource bundles for apps like reddit, need to research.

                    document("res/values/public.xml").use { publicDoc ->
                        val publicNode = publicDoc.getElementsByTagName("resources").item(0)
                        val resourceIds = mutableMapOf<String, Int>()

                        val definedIds = mutableSetOf<String>()
                        val resDirectories = get("res").listFiles { file -> file.isDirectory }

                        publicDoc.getElementsByTagName("public").apply {
                            for (i in 0 until this.length) {
                                val element = this.item(i) as Element
                                val idString = element.getAttribute("id")
                                val typeString = element.getAttribute("type")
                                val nameString = element.getAttribute("name")
                                val id = idString.substring(2).toInt(16)
                                if (id > resourceIds.getOrElse(typeString, { 0 })) {
                                    resourceIds[typeString] = id
                                }
                                // Update the reference string to remove the + after we create the ID.
                                definedIds.add("@$typeString/$nameString")
                            }
                        }

                        // Find all new ID declarations in layout/menu files so we can create a corresponding entry in ids.xml
                        // They will get added to public.xml later
                        document("res/values/ids.xml").use { idDoc ->
                            val idNode = idDoc.getElementsByTagName("resources").item(0)

                            resDirectories.filter { it.name.startsWith("layout") || it.name.startsWith("menu") }.forEach { dir ->
                                dir.listFiles { file -> file.isFile }.forEach { file ->
                                    document("res/${dir.name}/${file.name}").use { doc ->
                                        val deque = ArrayDeque<Element>()
                                        for (i in 0 until doc.childNodes.length) {
                                            deque.add(doc.childNodes.item(i) as Element)
                                        }
                                        while (deque.isNotEmpty()) {
                                            val element = deque.removeFirst()
                                            for (i in 0 until element.childNodes.length) {
                                                val childElem = element.childNodes.item(i) as? Element
                                                if (childElem != null) {
                                                    deque.add(childElem)
                                                }
                                            }
                                            val idString = element.getAttribute("android:id")
                                            if (idString.startsWith("@+id/")) {
                                                println("Adding $idString to ids.xml")
                                                val idName = idString.substring(5)
                                                val item = idDoc.createElement("id")
                                                item.setAttribute("name", idName)
                                                idNode.appendChild(item)
                                                element.setAttribute("android:id", "@id/$idName")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val resourceTypes = mapOf(
                            "attrs" to Pair("attr", "attr"),
                            "bools" to Pair("bool", "bool"),
                            "colors" to Pair("color", "color"),
                            "dimens" to Pair("dimen", "dimen"),
                            "drawables" to Pair("drawable", "drawable"),
                            "fonts" to Pair("font", "font"),
                            "fractions" to Pair("fraction", "fraction"),
                            "ids" to Pair("id", "id"),
                            "integers" to Pair("integer", "integer"),
                            "layouts" to Pair("layout", "layout"),
                            "plurals" to Pair("plurals", "plurals"),
                            "strings" to Pair("string", "string"),
                            "styles" to Pair("style", "style"),
                            "arrays" to Pair("string-array", "array")
                        )

                        val valuesDirectories = resDirectories.filter { it.name.startsWith("values") }

                        resourceTypes.forEach { resourceType, tagInfo ->
                            val xmlTagName = tagInfo.first
                            val publicTagName = tagInfo.second

                            valuesDirectories.forEach { dir ->
                                try {
                                    document("res/${dir.name}/$resourceType.xml").use { doc ->
                                        val elements = doc.getElementsByTagName(xmlTagName)
                                        for (i in 0 until elements.length) {
                                            val element = elements.item(i) as Element
                                            val resourceName = element.getAttribute("name")
                                            if (definedIds.contains("@$publicTagName/$resourceName")) {
                                                continue
                                            }

                                            println("Adding @$publicTagName/$resourceName to public.xml")
                                            val resourceId = resourceIds[publicTagName]!! + 1
                                            resourceIds[publicTagName] = resourceId
                                            val item = publicDoc.createElement("public")
                                            item.setAttribute("id", "0x${resourceId.toString(16)}")
                                            item.setAttribute("type", publicTagName)
                                            item.setAttribute("name", resourceName)
                                            publicNode.appendChild(item)
                                            definedIds.add("@$publicTagName/$resourceName")
                                        }
                                    }
                                } catch (_: FileNotFoundException) {
                                    // don't need to process
                                }
                            }
                        }

                        val fileResourceTypes = listOf(
                            "anim",
                            "color",
                            "drawable",
                            "font",
                            "interpolator",
                            "layout",
                            "menu",
                            "mipmap",
                            "raw",
                            "transition",
                            "xml"
                        )

                        fileResourceTypes.forEach { type ->
                            val directories = resDirectories.filter { it.name.startsWith(type) }
                            directories.forEach { dir ->
                                dir.listFiles { file -> file.isFile }
                                    .map{ file -> file.name.split(".").first() }
                                    .filter { !definedIds.contains("@$type/$it")}
                                    .forEach { name ->
                                        println("Adding @$type/$name to public.xml")
                                        val resourceId = resourceIds[type]!! + 1
                                        resourceIds[type] = resourceId
                                        val item = publicDoc.createElement("public")
                                        item.setAttribute("id", "0x${resourceId.toString(16)}")
                                        item.setAttribute("type", type)
                                        item.setAttribute("name", name)
                                        publicNode.appendChild(item)
                                        definedIds.add("@$type/$name")
                                }
                            }
                        }
                    }

                    val encoder = ApkModuleXmlEncoder()

                    val loadedModule = encoder.apkModule
                    loadedModule.setPreferredFramework(packageMetadata.apkInfo.androidFrameworkVersion)
                    packageMetadata.apkInfo.loadedFrameworks.forEach {
                        loadedModule.addExternalFramework(it)
                    }
                    encoder.scanDirectory(config.apkFiles)
                    loadedModule.writeApk(resources.resolve("resources.apk"))
                    loadedModule.close()
                }
            } else {
                null
            }

        /*
        val otherFiles =
            // TODO: Is this needed still?
            config.apkFiles.listFiles()!!.filter {
                // Excluded because present in resources.other.
                // TODO: We are reusing config.apkFiles as a temporarily directory for extracting resources.
                //  This is not ideal as it could conflict with files such as the ones that we filter here.
                //  The problem is that ResourcePatchContext#get returns a File relative to config.apkFiles,
                //  and we need to extract files to that directory.
                //  A solution would be to use config.apkFiles as the working directory for the patching process.
                //  Once all patches have been executed, we can move the decoded resources to a new directory.
                //  The filters wouldn't be needed anymore.
                //  For now, we assume that the files we filter here are not needed for the patching process.
                it.name != "AndroidManifest.xml" &&
                        it.name != "res" &&
                        // Generated by Androlib.
                        it.name != "build"
            }

        val otherResourceFiles =
            if (otherFiles.isNotEmpty()) {
                // Move the other resources files.
                resources.resolve("other").also { it.mkdirs() }.apply {
                    otherFiles.forEach { file ->
                        Files.move(file.toPath(), resolve(file.name).toPath())
                    }
                }
            } else {
                null
            }

        */

        // FIXME: All of this stuff is handled by arsclib using metadata files. Clean this up.
        return PatcherResult.PatchedResources(
            resourcesApkFile,
            null,
            emptySet(), //packageMetadata.apkInfo.uncompressedFiles,
            deleteResources,
        )
    }

    /**
     * Get a file from [PatcherConfig.apkFiles].
     *
     * @param path The path of the file.
     * @param copy Whether to copy the file from [PatcherConfig.apkFile] if it does not exist yet in [PatcherConfig.apkFiles].
     */
    operator fun get(
        path: String,
        copy: Boolean = true,
    ): File {
        if (path == "AndroidManifest.xml") {
            return config.apkFiles.resolve(path);
        }

        return config.resourceFiles.resolve(path).apply {
            // TODO: Remove this.
            if (copy && !exists()) {
                /*
                with(ExtFile(config.apkFile).directory) {
-                if (containsFile(path) || containsDir(path)) {
-                    copyToDir(config.apkFiles, path)
-                }
                 */
                // TODO: Handle this properly.
                // throw RuntimeException("File $path does not exist in temporary apk files directory ${config.resourceFiles.path}.")
            }
        }
    }

    /**
     * Mark a file for deletion when the APK is rebuilt.
     *
     * @param name The name of the file to delete.
     */
    fun delete(name: String) = deleteResources.add(name)

    /**
     * How to handle resources decoding and compiling.
     */
    internal enum class ResourceMode {
        /**
         * Decode and compile all resources.
         */
        FULL,

        /**
         * Only extract resources from the APK.
         * The AndroidManifest.xml and resources inside /res are not decoded or compiled.
         */
        RAW_ONLY,

        /**
         * Do not decode or compile any resources.
         */
        NONE,
    }
}
