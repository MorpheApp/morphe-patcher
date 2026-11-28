/*
 *  Copyright (C) 2022 github.com/REAndroid
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// https://github.com/REAndroid/APKEditor/blob/ea49069ddbaf14bddbd23836082c2794815b18d1/src/main/java/com/reandroid/apkeditor/merge/Merger.java
package app.morphe.patcher.apk

import app.morphe.patcher.apk.ApkUtils.writeAlignedApk
import app.morphe.patcher.logging.ArsclibLogger
import app.morphe.patcher.logging.Logger
import app.morphe.patcher.logging.NoOpLogger
import app.morphe.patcher.util.FileUtils.safelyDelete
import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ArchiveFile
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.DataType
import com.reandroid.utils.HexUtil
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.CoderMalfunctionError
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

@Suppress("unused")
class ApkMerger(
    private val logger: Logger = NoOpLogger
) {
    private val arsclibLogger = ArsclibLogger(logger)

    /** A step of [merge], reported as it starts. */
    sealed interface Step {
        /** Merging the module [moduleName], such as `split_config.arm64_v8a.apk`, into the APK. */
        class Merging(val moduleName: String) : Step

        /** Writing the merged APK. */
        data object Writing : Step
    }

    /**
     * Merges the modules of a split archive into one APK that installs on its own.
     *
     * @param inputFile The split archive, or a directory holding its modules.
     * @param outputFile The file to write the merged APK to.
     * @param validateModules Whether to refuse modules that do not belong together, such as those
     *   of another app or another version of it.
     * @param resDirName The directory to move the resource files into, or null to leave them.
     * @param validateResDir Whether to give the resource files the paths their types name.
     * @param extractNativeLibs Whether the native libraries are extracted on install, or null to
     *   keep what the manifest of the base says.
     * @param cleanMetaInf Ignored: META-INF is always kept, so the signature block is retained.
     * @param onStep Called as each [Step] of the merge starts.
     *
     * @throws IOException If the modules cannot be read or merged.
     */
    @Throws(IOException::class)
    fun merge(
        inputFile: File,
        outputFile: File,
        validateModules: Boolean = true,
        resDirName: String? = null,
        validateResDir: Boolean = true,
        extractNativeLibs: Boolean? = null,
        @Suppress("UNUSED_PARAMETER") cleanMetaInf: Boolean = false,
        onStep: (Step) -> Unit = {},
    ) {
        outputFile.safelyDelete()
        val extracted = inputFile.isFile
        val dir = if (extracted) extractModules(inputFile) else inputFile
        try {
            ApkBundle().use { bundle ->
                bundle.setAPKLogger(StepLogger(onStep))
                val merged = readingResources {
                    bundle.loadApkDirectory(dir, false)
                    logger.info("Found modules: " + bundle.apkModuleList.size)
                    bundle.mergeModules(!validateModules)
                }
                merged.use {
                    if (resDirName != null) {
                        logger.info("Renaming resources root dir: $resDirName")
                        merged.setResourcesRootDir(resDirName)
                    }
                    if (validateResDir) {
                        logger.info("Validating resources dir ...")
                        merged.validateResourcesDir()
                    }
                    sanitizeManifest(merged)
                    merged.refreshTable()
                    merged.refreshManifest()
                    val shouldExtractNativeLibs = extractNativeLibs ?: merged.androidManifest?.isExtractNativeLibs
                    logger.info("Setting extractNativeLibs=$shouldExtractNativeLibs")
                    merged.setExtractNativeLibs(shouldExtractNativeLibs)

                    onStep(Step.Writing)
                    logger.info("Writing apk ...")
                    outputFile.parentFile?.mkdirs()
                    merged.writeAlignedApk(outputFile)
                }
            }
        } finally {
            if (extracted) dir.safelyDelete()
        }
        logger.info("Saved to: $outputFile")
    }

    // ARSCLib fails with these on resources it cannot make sense of, which tells the user nothing
    private inline fun <T> readingResources(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        if (error.isUnreadableResources() || error.cause?.isUnreadableResources() == true) {
            throw IOException(
                "Failed to merge split APK resources. The split set may be incomplete, corrupted, or unsupported.",
                error
            )
        }
        throw error
    }

    private fun Throwable.isUnreadableResources() =
        this is CoderMalfunctionError || this is IllegalArgumentException && message?.contains("newPosition > limit") == true

    /**
     * What ARSCLib says while merging, kept at trace level. It reports nothing but its log, so the
     * module it merges is read off the line it logs for it.
     */
    private inner class StepLogger(private val onStep: (Step) -> Unit) : APKLogger {
        override fun logMessage(msg: String) {
            logger.trace(msg)
            reportMerging(msg)
        }

        override fun logError(msg: String, tr: Throwable?) {
            logger.trace(tr?.let { "$msg\n${it.stackTraceToString()}" } ?: msg)
        }

        override fun logVerbose(msg: String) {
            logger.trace(msg)
        }

        private fun reportMerging(msg: String) {
            val name = MERGING_LINE.matchEntire(msg)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return
            onStep(Step.Merging(if (name.endsWith(".apk", ignoreCase = true)) name else "$name.apk"))
        }
    }

    /**
     * Extract the OBB files of a bundle, so that patches can read them.
     *
     * A bundle such as `.xapk` can carry the OBB expansion files of an app next to its APK modules.
     * Every file under `Android/obb/`, where the format puts them, is extracted, and so is every file
     * with the `.obb` extension, so a bundle that keeps the OBB somewhere else still hands it over.
     * No other file of the bundle is extracted. The files are copied to [outputDir] under the path
     * they have inside the bundle, and are not merged into the APK.
     *
     * An OBB is copied as it is, without reading its format, which may be anything the app supports.
     *
     * Entries whose path would leave [outputDir], such as `../x.obb` or an absolute path, are skipped
     * and logged.
     *
     * A bundle without OBB files, such as a plain APK, yields an empty map.
     *
     * Pass the result to [app.morphe.patcher.PatcherConfig] as `obbFiles`.
     *
     * @param bundleFile The bundle to extract the files from.
     * @param outputDir The directory to extract the files to. The caller owns it, and must keep it
     * for as long as the extracted files are needed.
     * @return The extracted files, keyed by the path they have inside the bundle, such as
     * `Android/obb/com.example/main.1.com.example.obb`.
     * @throws FileNotFoundException If [bundleFile] does not exist or is not a file.
     * @throws IOException If the bundle cannot be read, or an OBB file could not be extracted.
     */
    @Throws(IOException::class)
    fun extractObbFiles(bundleFile: File, outputDir: File): Map<String, File> {
        if (!bundleFile.isFile) throw FileNotFoundException("Bundle not found: $bundleFile")

        val outputRoot = outputDir.canonicalFile.toPath()
        return ArchiveFile(bundleFile).use { archive ->
            val names = archive.iterator().asSequence()
                .map { it.name }
                .filter { !it.endsWith("/") && (it.endsWith(".obb", true) || it.startsWith(OBB_DIRECTORY, true)) }
                // An archive can hold the same name more than once, and only one file is written for it.
                .distinct()
                .filter { name ->
                    name.isContainedPath(outputDir, outputRoot).also { contained ->
                        if (!contained) logger.warn("Skipping OBB file with a path outside the output directory: $name")
                    }
                }
                .toList()

            if (names.isEmpty()) return@use emptyMap()

            logger.info("Extracting ${names.size} OBB file(s) to: $outputDir")
            outputDir.mkdirs()
            val extracted = names.toHashSet()

            // The permissions the archive declares can leave the files unreadable for a patch.
            fixFilePermissions(archive)
            archive.extractAll(outputDir, { it.name in extracted }, arsclibLogger)

            names.associateWith { name ->
                val file = File(outputDir, name)
                if (!file.isFile) {
                    throw IOException("Failed to extract OBB file $name to: $file")
                }
                if (!file.canonicalFile.toPath().startsWith(outputRoot)) {
                    throw IOException("OBB file $name was extracted outside the output directory: ${file.canonicalFile}")
                }
                file
            }
        }
    }

    /**
     * Whether the path of an archive entry stays below [outputDir].
     *
     * An archive can name an entry `../../shared_prefs/x.obb` or `C:/x.obb`, which would write it
     * outside that directory. The path is resolved before anything is extracted, since a file already
     * written outside cannot be taken back.
     */
    private fun String.isContainedPath(outputDir: File, outputRoot: Path): Boolean {
        if (isEmpty() || startsWith("/") || contains('\\') || contains(':')) return false
        if (split('/').any { it == ".." || it == "." }) return false
        return try {
            File(outputDir, this).canonicalFile.toPath().startsWith(outputRoot)
        } catch (ex: IOException) {
            logger.warn("Failed to resolve the path of OBB file $this: $ex")
            false
        }
    }

    // Only the modules are extracted, the root of the archive being where they sit
    @Throws(IOException::class)
    private fun extractModules(file: File): File {
        val dir = toTmpDir(file)
        logger.info("Extracting to: $dir")
        dir.mkdirs()
        val root = dir.toPath().normalize()
        ZipFile(file).use { zip ->
            val modules = SplitArchive.modules(zip).toList()
            if (modules.isEmpty()) throw IOException("No *.apk files found on: $file")
            modules.forEach { module ->
                // The archive picks the name, so the file it lands in is checked before anything is written
                val target = root.resolve(module.name).normalize()
                if (!target.startsWith(root)) {
                    throw IOException("Module ${module.name} would be extracted outside: $dir")
                }
                zip.getInputStream(module).use { input -> Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
        return dir
    }

    private fun fixFilePermissions(archive: ArchiveFile) {
        val rwAll = 438 // equivalent to chmod 666
        val iterator = archive.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.getCentralEntryHeader().filePermissions.permissions(rwAll)
        }
    }

    private fun toTmpDir(file: File): File {
        val name = HexUtil.toHex8("tmp_", file.name.hashCode())
        val tmp = file.parentFile?.let { File(it, name) } ?: File(name)
        return ApkEditorUtil.ensureUniqueFile(tmp)
    }

    // Strips what ties the base to its splits, so the merged APK installs as a single package
    private fun sanitizeManifest(module: ApkModule) {
        if (!module.hasAndroidManifest()) return
        logger.info("Sanitizing manifest ...")

        module.androidManifest.apply {
            arrayOf(
                AndroidManifest.ID_isSplitRequired,
                AndroidManifest.ID_requiredSplitTypes,
                AndroidManifest.ID_splitTypes
            ).forEach { id ->
                applicationElement.removeAttributesWithId(id)
                manifestElement.removeAttributesWithId(id)
            }
            arrayOf(AndroidManifest.NAME_requiredSplitTypes, AndroidManifest.NAME_splitTypes).forEach { name ->
                manifestElement.removeAttributeIf { it.name == name }
            }

            manifestElement.removeElementsIf { it.name == "uses-split" }
            arrayOf("splitName", "split").forEach { name ->
                manifestElement.removeAttributeIf { it.name == name }
                applicationElement.removeAttributeIf { it.name == name }
            }

            applicationElement.removeElementsIf { element ->
                if (element.name != AndroidManifest.TAG_meta_data) return@removeElementsIf false
                val name = element.getAttributes { it.nameId == AndroidManifest.ID_name }
                    .asSequence().singleOrNull()?.valueString
                    ?: return@removeElementsIf false
                val splitMetaData = when {
                    name == "com.android.dynamic.apk.fused.modules" ->
                        element.getAttributes { it.nameId == AndroidManifest.ID_value }
                            .asSequence().firstOrNull()?.valueString == "base"
                    name.startsWith("com.android.vending.") -> true
                    name.startsWith("com.android.stamp.") -> true
                    else -> false
                }
                if (splitMetaData && name == "com.android.vending.splits") removeSplitsResource(module, element)
                splitMetaData
            }

            refresh()
        }
    }

    // The resource lists the splits the app was built with, none of which is left to list
    private fun removeSplitsResource(module: ApkModule, element: ResXmlElement) {
        if (!module.hasTableBlock()) return
        val value = element
            .getAttributes { it.nameId == AndroidManifest.ID_value || it.nameId == AndroidManifest.ID_resource }
            .asSequence()
            .firstOrNull()
            ?.takeIf { it.dataType == DataType.REFERENCE }
            ?: return

        val table = module.tableBlock
        val resource = table.getResource(value.data) ?: return
        for (entry in resource) {
            val resEntry = entry ?: continue
            resEntry.resValue?.dataAsString?.takeUnless { it.isBlank() }?.let { path ->
                module.zipEntryMap.remove(path)
                logger.trace("Removed table entry $path")
            }
            // It's not safe to destroy entry, resource id might be used in dex code.
            // Better replace it with boolean value.
            resEntry.isNull = true
            resEntry.typeBlock.parentSpecTypePair.removeNullEntries(resEntry.id)
        }
        table.refresh()
    }

    private companion object {
        /**
         * The directory of a bundle that holds the OBB files of the app, which patches can read.
         */
        private const val OBB_DIRECTORY = "Android/obb/"

        /** The line ARSCLib logs for each module it merges. */
        private val MERGING_LINE = Regex("Merging: (.+)")
    }
}