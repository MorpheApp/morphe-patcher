/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.io.InputStream
import java.util.logging.Logger

internal object DexReadWrite {
    /**
     * Reads a multidex file and returns a [DexFile] containing all classes from all dex files in the multidex file.
     * @param inputFile The multidex file to read.
     * @param logger An optional logger to log the loading process.
     *
     * @return A [DexFile] containing all classes from all dex files in the multidex file.
     */
    internal fun readMultidexFile(inputFile: File, logger: Logger? = null): DexFile {
        require(inputFile.exists()) { "input file does not exist: $inputFile" }

        val container = DexFileFactory.loadDexContainer(inputFile, null)
        logger?.info("Loaded multidex file: $inputFile with ${container.dexEntryNames.size} dex files")
        val dexFiles = container.dexEntryNames.map { entry ->
            container.getEntry(entry)!!.dexFile
        }

        return object : DexFile {
            override fun getClasses(): Set<ClassDef> {
                return dexFiles.flatMap { it.classes }.toSet()
            }

            override fun getOpcodes(): Opcodes {
                return dexFiles.first().opcodes
            }
        }
    }

    /**
     * Reads a dex file from an [InputStream] and returns a [DexFile] containing all classes from the dex file.
     * @param inputStream The [InputStream] to read the dex file from.
     *
     * @return A [DexFile] containing all classes from the dex file.
     */
    internal fun readDexStream(inputStream: InputStream): DexFile {
        // This doesn't handle ODEX/OAT files, but we don't need to handle those for our use case, so it's fine.
        // Normally DexFileFactory would take care of this, but it doesn't support reading from streams, so we have to do it ourselves.
        return DexBackedDexFile.fromInputStream(null, inputStream);
    }

    /**
     * Writes a [DexFile] to a multidex file in the specified output directory. The dex file will be split into multiple dex files if it exceeds the dex file size limit.
     * @param outputDir The directory to write the multidex file to.
     * @param dexFile The [DexFile] to write.
     * @param maxThreads The maximum number of threads to use for writing the dex files. (Currently ignored.)
     *
     * @return A list of [File]s representing the written dex files.
     */
    internal fun writeMultiDexFile(outputDir: File, dexFile: DexFile, maxThreads: Int = -1, logger: Logger? = null): List<File> {
        require(!outputDir.exists() || outputDir.isDirectory) { "output path must be a directory: $outputDir" }

        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        // TODO: Handle multi threaded writing of dex files

        val sortedClasses = ArrayDeque(dexFile.classes.sortedBy { it.type })

        val dexFiles = mutableListOf<File>()
        var currentDexPool = DexPool(dexFile.opcodes)

        while (sortedClasses.isNotEmpty()) {
            val classDef = sortedClasses.first()

            currentDexPool.mark()
            currentDexPool.internClass(classDef)
            if (currentDexPool.hasOverflowed()) {
                currentDexPool.reset()
                dexFiles.add(writeDexPool(currentDexPool, outputDir, dexFiles.size, logger))

                currentDexPool = DexPool(dexFile.opcodes)
            } else {
                sortedClasses.removeFirst()
            }
        }

        dexFiles.add(writeDexPool(currentDexPool, outputDir, dexFiles.size, logger))
        logger?.info("Wrote ${dexFiles.size} dex files to $outputDir")
        return dexFiles
    }

    private fun writeDexPool(dexPool: DexPool, outputDir: File, dexNum: Int, logger: Logger?): File {
        val fileName = if (dexNum == 0) { "classes.dex" } else { "classes${dexNum + 2}.dex" }
        val file = outputDir.resolve(fileName)
        logger?.info("Writing $fileName")
        dexPool.writeTo(FileDataStore(file))
        return file
    }
}