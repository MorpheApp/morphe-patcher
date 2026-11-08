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
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ArchiveEntry
import com.reandroid.archive.ArchiveFile
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import com.reandroid.utils.HexUtil
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path
import java.util.function.Predicate

@Suppress("unused")
class ApkMerger(
    private val logger: Logger = NoOpLogger
) {
    private val arsclibLogger = ArsclibLogger(logger)

    /**
     * @param cleanMetaInf Ignored: META-INF is always kept, so the signature block is retained.
     */
    fun merge(
        inputFile: File,
        outputFile: File,
        validateModules: Boolean = true,
        resDirName: String? = null,
        validateResDir: Boolean = true,
        extractNativeLibs: Boolean? = null,
        @Suppress("UNUSED_PARAMETER") cleanMetaInf: Boolean = false
    ) {
        outputFile.safelyDelete()
        var dir: File = inputFile
        var extracted = false
        if (dir.isFile) {
            dir = extractFile(dir)
            extracted = true
        }
        logger.info("Searching apk files ...")
        val bundle = ApkBundle()
        bundle.setAPKLogger(arsclibLogger)
        bundle.loadApkDirectory(dir, extracted)
        logger.info("Found modules: " + bundle.apkModuleList.size)
        val mergedModule = bundle.mergeModules(validateModules)
        if (resDirName != null) {
            logger.info("Renaming resources root dir: $resDirName")
            mergedModule.setResourcesRootDir(resDirName)
        }
        if (validateResDir) {
            logger.info("Validating resources dir ...")
            mergedModule.validateResourcesDir()
        }
        sanitizeManifest(mergedModule)
        mergedModule.refreshTable()
        mergedModule.refreshManifest()
        val shouldExtractNativeLibs = extractNativeLibs ?: mergedModule.androidManifest?.isExtractNativeLibs
        logger.info("Setting extractNativeLibs=$shouldExtractNativeLibs")
        mergedModule.setExtractNativeLibs(shouldExtractNativeLibs)
        logger.info("Writing apk ...")
        mergedModule.writeAlignedApk(outputFile)
        mergedModule.close()
        bundle.close()
        if (extracted) {
            dir.safelyDelete()
            dir.deleteOnExit()
        }
        logger.info("Saved to: $outputFile")
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
            archive.extractAll(outputDir, Predicate { it.name in extracted }, arsclibLogger)

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

    @Throws(IOException::class)
    private fun extractFile(file: File): File {
        val tmp = toTmpDir(file)
        logger.info("Extracting to: $tmp")
        if (tmp.exists()) {
            logger.info("Delete: $tmp")
            tmp.safelyDelete()
        }
        tmp.deleteOnExit()
        val archive = ArchiveFile(file)
        fixFilePermissions(archive)
        val filter = Predicate { archiveEntry: ArchiveEntry? -> archiveEntry!!.name.endsWith(".apk") }
        val count = archive.extractAll(tmp, filter, arsclibLogger)
        archive.close()
        if (count == 0) {
            throw IOException("No *.apk files found on: $file")
        }
        return tmp
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

    private fun sanitizeManifest(apkModule: ApkModule) {
        if (!apkModule.hasAndroidManifest()) {
            return
        }
        val manifest = apkModule.getAndroidManifest()
        logger.info("Sanitizing manifest ...")

        AndroidManifestHelper.removeAttributeFromManifestById(
            manifest,
            AndroidManifest.ID_requiredSplitTypes, arsclibLogger
        )
        AndroidManifestHelper.removeAttributeFromManifestById(
            manifest,
            AndroidManifest.ID_splitTypes, arsclibLogger
        )
        AndroidManifestHelper.removeAttributeFromManifestByName(
            manifest,
            AndroidManifest.NAME_splitTypes, arsclibLogger
        )

        AndroidManifestHelper.removeAttributeFromManifestByName(
            manifest,
            AndroidManifest.NAME_requiredSplitTypes, arsclibLogger
        )
        AndroidManifestHelper.removeAttributeFromManifestByName(
            manifest,
            AndroidManifest.NAME_splitTypes, arsclibLogger
        )
        AndroidManifestHelper.removeAttributeFromManifestAndApplication(
            manifest,
            AndroidManifest.ID_isSplitRequired,
            arsclibLogger, AndroidManifest.NAME_isSplitRequired
        )
        val application = manifest.getApplicationElement()
        val splitMetaDataElements: MutableList<ResXmlElement> =
            AndroidManifestHelper.listSplitRequired(application)
        var splitsRemoved = false
        for (meta in splitMetaDataElements) {
            if (!splitsRemoved) {
                splitsRemoved = removeSplitsTableEntry(meta, apkModule)
            }
            logger.info(
                ("Removed-element : <" + meta.name + "> name=\""
                        + AndroidManifestBlock.getAndroidNameValue(meta) + "\"")
            )
            application.remove(meta)
        }
        manifest.refresh()
    }

    private fun removeSplitsTableEntry(metaElement: ResXmlElement, apkModule: ApkModule): Boolean {
        val nameAttribute = metaElement.searchAttributeByResourceId(AndroidManifest.ID_name) ?: return false
        if ("com.android.vending.splits" != nameAttribute.getValueAsString()) {
            return false
        }
        var valueAttribute = metaElement.searchAttributeByResourceId(
            AndroidManifest.ID_value
        )
        if (valueAttribute == null) {
            valueAttribute = metaElement.searchAttributeByResourceId(
                AndroidManifest.ID_resource
            )
        }
        if (valueAttribute == null
            || valueAttribute.valueType != ValueType.REFERENCE
        ) {
            return false
        }
        if (!apkModule.hasTableBlock()) {
            return false
        }
        val tableBlock = apkModule.tableBlock
        val resourceEntry = tableBlock.getResource(valueAttribute.data) ?: return false
        val zipEntryMap = apkModule.zipEntryMap
        for (entry in resourceEntry) {
            if (entry == null) {
                continue
            }
            val resValue = entry.getResValue() ?: continue
            val path = resValue.getValueAsString()
            logger.info("Removed-table-entry : $path")
            //Remove file entry
            zipEntryMap.remove(path)
            // It's not safe to destroy entry, resource id might be used in dex code.
            // Better replace it with boolean value.
            entry.isNull = true
            val specTypePair = entry.typeBlock.parentSpecTypePair
            specTypePair.removeNullEntries(entry.getId())
        }
        return true
    }

    private companion object {
        /**
         * The directory of a bundle that holds the OBB files of the app, which patches can read.
         */
        private const val OBB_DIRECTORY = "Android/obb/"
    }
}