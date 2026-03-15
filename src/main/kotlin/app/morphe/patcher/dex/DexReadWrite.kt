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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.logging.Logger
import kotlin.math.max
import kotlin.math.min

internal object DexReadWrite {
    private const val MIN_CLASSES_PER_SEGMENT = 1000
    private const val MAX_THREADS = 4

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

        val opcodes = dexFiles.maxByOrNull { it.opcodes.api }!!.opcodes

        return object : DexFile {
            override fun getClasses(): Set<ClassDef> {
                return dexFiles.flatMap { it.classes }.toSet()
            }

            override fun getOpcodes(): Opcodes {
                return opcodes
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
        return DexBackedDexFile.fromInputStream(null, BufferedInputStream(inputStream))
    }

    /**
     * Writes a [DexFile] to the specified output directory.
     * The dex file will be split into multiple dex files if it exceeds the dex size limit.
     * @param outputDir The directory to write the multidex file to.
     * @param dexFile The [DexFile] to write.
     * @param maxThreads The maximum number of threads to use for writing the dex files.
     *
     * @return A list of [File]s representing the written dex files.
     */
    internal fun writeMultiDexFile(outputDir: File, dexFile: DexFile, maxThreads: Int = -1, logger: Logger? = null): List<File> {
        require(!outputDir.exists() || outputDir.isDirectory) { "Output path must be a directory: $outputDir" }

        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        val availableProcessors = Runtime.getRuntime().availableProcessors()
        val actualMaxThreads = if (maxThreads < 1) {
            min(availableProcessors, MAX_THREADS)
        } else {
            min(min(maxThreads, MAX_THREADS), availableProcessors)
        }

        val classesAsList = dexFile.classes.toList()

        val numSegments = max(1, min(actualMaxThreads, classesAsList.size / MIN_CLASSES_PER_SEGMENT))

        val segmentResults = if (numSegments == 1) {
            logger?.info("Processing ${classesAsList.size} classes (single threaded mode)")

            listOf(processSegment(classesAsList, dexFile.opcodes, outputDir, 0))
        } else {
            val segments = splitIntoSegments(classesAsList, numSegments)

            logger?.info("Processing ${classesAsList.size} classes in parallel (${segments.size} threads)")

            val dispatcher = Dispatchers.Default.limitedParallelism(numSegments)
            runBlocking(dispatcher) {
                segments.mapIndexed { segmentIndex, classes ->
                    async {
                        processSegment(classes, dexFile.opcodes, outputDir, segmentIndex)
                    }
                }.awaitAll()
            }
        }

        // Rename temp files to final dex files
        val dexFiles = mutableListOf<File>()
        for (tempFiles in segmentResults) {
            for (tempFile in tempFiles) {
                val fileName = if (dexFiles.isEmpty()) "classes.dex" else "classes${dexFiles.size + 1}.dex"
                val finalFile = outputDir.resolve(fileName)
                tempFile.renameTo(finalFile)
                dexFiles.add(finalFile)
            }
        }

        logger?.info("Wrote ${dexFiles.size} dex files to $outputDir")
        return dexFiles
    }

    /**
     * Splits a list into [numSegments] contiguous segments of roughly equal size.
     */
    private fun <T> splitIntoSegments(list: List<T>, numSegments: Int): List<List<T>> {
        if (numSegments <= 1) return listOf(list)

        val segmentSize = list.size / numSegments
        val remainder = list.size % numSegments
        val segments = mutableListOf<List<T>>()
        var offset = 0

        for (i in 0 until numSegments) {
            // Distribute the remainder across the first segments (one extra element each).
            val size = segmentSize + if (i < remainder) 1 else 0
            segments.add(list.subList(offset, offset + size))
            offset += size
        }

        return segments
    }

    /**
     * Processes a segment of classes: fills one or more [DexPool]s and writes each to a temp file.
     * Returns the list of temp files in the order they were produced.
     */
    private fun processSegment(
        classes: List<ClassDef>,
        opcodes: Opcodes,
        outputDir: File,
        segmentIndex: Int,
    ): List<File> {
        val tempFiles = mutableListOf<File>()
        var currentDexPool = DexPool(opcodes)
        val classQueue = ArrayDeque(classes)

        while (classQueue.isNotEmpty()) {
            val classDef = classQueue.first()

            currentDexPool.mark()
            currentDexPool.internClass(classDef)
            if (currentDexPool.hasOverflowed()) {
                currentDexPool.reset()
                tempFiles.add(writeDexPoolToTemp(currentDexPool, outputDir, segmentIndex, tempFiles.size))
                currentDexPool = DexPool(opcodes)
            } else {
                classQueue.removeFirst()
            }
        }

        tempFiles.add(writeDexPoolToTemp(currentDexPool, outputDir, segmentIndex, tempFiles.size))
        return tempFiles
    }

    /**
     * Writes a [DexPool] to a temporary file within the output directory.
     * The temp file is named using the segment and pool indices to avoid collisions.
     */
    private fun writeDexPoolToTemp(dexPool: DexPool, outputDir: File, segmentIndex: Int, poolIndex: Int): File {
        val tempFile = outputDir.resolve(".tmp_seg${segmentIndex}_${poolIndex}.dex")
        dexPool.writeTo(FileDataStore(tempFile))
        return tempFile
    }
}