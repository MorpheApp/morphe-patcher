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

import app.morphe.patcher.logging.ArsclibLogger
import app.morphe.patcher.logging.Logger
import app.morphe.patcher.logging.NoOpLogger
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
import java.io.IOException
import java.util.function.Predicate
import java.util.regex.Pattern


class ApkMerger(
    private val logger: Logger = NoOpLogger
) {
    private val arsclibLogger = ArsclibLogger(logger)

    fun merge(
        inputFile: File,
        outputFile: File,
        validateModules: Boolean = true,
        resDirName: String? = null,
        validateResDir: Boolean = true,
        extractNativeLibs: Boolean? = null,
        cleanMetaInf: Boolean = true
    ) {
        ApkEditorUtil.delete(outputFile)
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
        if (cleanMetaInf) {
            logger.info("Clearing META-INF ...")
            clearMeta(mergedModule)
        }
        sanitizeManifest(mergedModule)
        mergedModule.refreshTable()
        mergedModule.refreshManifest()
        val shouldExtractNativeLibs = extractNativeLibs ?: mergedModule.androidManifest?.isExtractNativeLibs
        logger.info("Setting extractNativeLibs=$shouldExtractNativeLibs")
        mergedModule.setExtractNativeLibs(shouldExtractNativeLibs)
        logger.info("Writing apk ...")
        mergedModule.writeApk(outputFile)
        mergedModule.close()
        bundle.close()
        if (extracted) {
            ApkEditorUtil.deleteDir(dir)
            dir.deleteOnExit()
        }
        logger.info("Saved to: $outputFile")
    }

    @Throws(IOException::class)
    private fun extractFile(file: File): File {
        val tmp = toTmpDir(file)
        logger.info("Extracting to: $tmp")
        if (tmp.exists()) {
            logger.info("Delete: $tmp")
            ApkEditorUtil.deleteDir(tmp)
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
        val rw_all = 438 // equivalent to chmod 666
        val iterator = archive.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.getCentralEntryHeader().filePermissions.permissions(rw_all)
        }
    }

    private fun toTmpDir(file: File): File {
        var name = file.name
        name = HexUtil.toHex8("tmp_", name.hashCode())
        val dir = file.parentFile
        var tmp: File
        if (dir == null) {
            tmp = File(name)
        } else {
            tmp = File(dir, name)
        }
        tmp = ApkEditorUtil.ensureUniqueFile(tmp)
        return tmp
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
    private fun clearMeta(module: ApkModule) {
        val archive = module.zipEntryMap
        archive.removeIf(Pattern.compile("^META-INF/.+\\.(([MS]F)|(RSA))"))
        archive.remove("stamp-cert-sha256")
        module.apkSignatureBlock = null
    }
}