/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

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
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Enumeration
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.collections.ArrayDeque
import kotlin.collections.Collection
import kotlin.collections.HashSet
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableCollection
import kotlin.collections.Set
import kotlin.collections.associate
import kotlin.collections.first
import kotlin.collections.flatMap
import kotlin.collections.isNotEmpty
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mapIndexed
import kotlin.collections.mapTo
import kotlin.collections.maxByOrNull
import kotlin.collections.mutableListOf
import kotlin.collections.plusAssign
import kotlin.collections.toList
import kotlin.collections.toSet
import kotlin.collections.zip
import kotlin.math.max
import kotlin.math.min

/**
 * The result of reading a multidex file, containing both the merged [DexFile] view
 * and the names of each individual DEX entry within the container.
 *
 * @param dexFile A merged [DexFile] containing all classes from all DEX entries.
 * @param extractedDexFiles The names of each original DEX entry (e.g., "classes.dex", "classes2.dex").
 * @param classDescriptorsByEntry Maps each DEX entry name to the set of class descriptors it contains.
 */
internal class MultidexReadResult(
    val dexFile: DexFile,
    val extractedDexFiles: List<File>,
    val classDescriptorsByEntry: Map<String, Set<String>>,
)

internal object DexReadWrite {
    private const val MIN_CLASSES_PER_SEGMENT = 1000
    private const val MAX_THREADS = 4
    private val DESUGAR_NAMESPACES = setOf(
        "Lj$/",
        $$"L$r8$"
    )

    /**
     * Reads a multidex file and returns a [MultidexReadResult] containing a merged [DexFile]
     * with all classes and the names of each DEX entry within the container.
     *
     * @param inputFile The multidex file to read.
     * @param logger An optional logger to log the loading process.
     *
     * @return A [MultidexReadResult] with the merged DexFile and entry names.
     */
    internal fun readMultidexFileFromZip(inputFile: File, outputDir: File, logger: Logger? = null): MultidexReadResult {
        require(inputFile.exists()) { "input file does not exist: $inputFile" }

        val extractedFiles = extractDexEntries(inputFile, outputDir)
        logger?.info("Loaded multidex file: $inputFile with ${extractedFiles.size} dex files")

        val memoryMappedDexFiles = extractedFiles.map { file ->
            val rwFile = RandomAccessFile(file, "rw")
            val mappedByteBuffer = rwFile.channel.map(FileChannel.MapMode.READ_WRITE, 0, rwFile.channel.size())
            DexBackedDexFile(null, mappedByteBuffer)
        }
        val entryNames = extractedFiles.map { file -> file.name }

        // Track which class descriptors belong to which DEX entry.
        val classDescriptorsByEntry = entryNames.zip(memoryMappedDexFiles).associate { (name, dex) ->
            name to dex.classes.let { classes ->
                classes.mapTo(HashSet(2 * classes.size)) { it.type }
            }
        }

        val opcodes = memoryMappedDexFiles.maxByOrNull { it.opcodes.api }!!.opcodes

        val mergedDexFile = object : DexFile {
            override fun getClasses(): Set<ClassDef> {
                return memoryMappedDexFiles.flatMap { it.classes }.toSet()
            }

            override fun getOpcodes(): Opcodes {
                return opcodes
            }
        }

        return MultidexReadResult(mergedDexFile, extractedFiles, classDescriptorsByEntry)
    }

    /**
     * Extracts raw DEX entries from an APK/ZIP file directly to an output directory.
     * This reads from the zip without dexlib2 parsing, so the output files are
     * byte-identical to the original DEX data in the APK.
     *
     * @param apkFile The APK file to extract from.
     * @param outputDir The directory to write the extracted DEX files to.
     * @return A list of extracted files.
     */
    internal fun extractDexEntries(apkFile: File, outputDir: File): List<File> {
        outputDir.mkdirs()
        return ZipFile(apkFile).use { zip ->
            val outputFiles = mutableListOf<File>()
            val entriesEnumeration: Enumeration<out ZipEntry> = zip.entries()
            while (entriesEnumeration.hasMoreElements()) {
                val entry: ZipEntry = entriesEnumeration.nextElement()
                val name = entry.name
                if (!name.startsWith("classes") || !name.endsWith(".dex")) {
                    continue
                }

                val outputFile = outputDir.resolve(entry.name)
                zip.getInputStream(entry).use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                    outputFiles += outputFile
                }
            }

            outputFiles
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

        // TODO: Load extensions in memory mapped fashion?
        return DexBackedDexFile.fromInputStream(null, BufferedInputStream(inputStream))
    }

    /**
     * Writes a [DexFile] to the specified output directory.
     * The dex file will be split into multiple dex files if it exceeds the dex size limit.
     * @param outputDir The directory to write the multidex file to.
     * @param classDefs The class definitions to write. This collection will be cleared after splitting into segments.
     * @param opcodes The [Opcodes] to use for the dex files.
     * @param maxThreads The maximum number of threads to use for writing the dex files.
     *
     * @return A list of [File]s representing the written dex files.
     */
    internal fun writeMultiDexFile(
        outputDir: File,
        classDefs: MutableCollection<ClassDef>,
        opcodes: Opcodes,
        maxThreads: Int = -1,
        logger: Logger? = null
    ): List<File> {
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

        val numClasses = classDefs.size

        val numSegments = max(1, min(actualMaxThreads, numClasses / MIN_CLASSES_PER_SEGMENT))

        // Split into independent mutable lists and clear the original collection to reduce memory usage.
        val segments = splitIntoMutableSegments(classDefs, numSegments)
        classDefs.clear()

        val segmentResults = if (numSegments == 1) {
            logger?.info("Processing $numClasses classes (single threaded mode)")

            segments.mapIndexed { index, segment ->
                processSegment(segment, opcodes, outputDir, index)
            }
        } else {
            logger?.info("Processing $numClasses classes in parallel (${actualMaxThreads} threads)")

            val dispatcher = Dispatchers.Default.limitedParallelism(numSegments)
            runBlocking(dispatcher) {
                segments.mapIndexed { segmentIndex, classQueue ->
                    async {
                        processSegment(classQueue, opcodes, outputDir, segmentIndex)
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
     * Splits a collection into [numSegments] independent mutable lists of roughly equal size.
     * Unlike [List.subList], the returned lists do not share backing storage with each other
     * or the source collection, so they can be independently cleared to free memory.
     */
    private fun splitIntoMutableSegments(collection: Collection<ClassDef>, numSegments: Int): List<ArrayDeque<ClassDef>> {
        var requestedSegments = if (numSegments <= 1) {
            1
        } else {
            numSegments
        }
        val segments = mutableListOf<ArrayDeque<ClassDef>>()

        // Separate out any j$ classes into their own DEX files, because d8/r8 will reject the DEX files otherwise.
        val desugarClassDefs: List<ClassDef> = collection.filter { isDesugarLib(it) }
        if (desugarClassDefs.isNotEmpty()) {
            segments.add(ArrayDeque(desugarClassDefs))
            if (requestedSegments > 1) {
                requestedSegments--
            }
        }

        val list = collection.filter { !isDesugarLib(it) }
        val segmentSize = list.size / requestedSegments
        val remainder = list.size % requestedSegments
        var offset = 0

        for (i in 0 until requestedSegments) {
            // Distribute the remainder across the first segments (one extra element each).
            val size = segmentSize + if (i < remainder) 1 else 0
            segments.add(ArrayDeque(list.subList(offset, offset + size)))
            offset += size
        }

        return segments
    }

    /**
     * Processes a segment of classes: fills one or more [DexPool]s and writes each to a temp file.
     * Returns the list of temp files in the order they were produced.
     */
    private fun processSegment(
        classQueue: ArrayDeque<ClassDef>,
        opcodes: Opcodes,
        outputDir: File,
        segmentIndex: Int,
    ): List<File> {
        val tempFiles = mutableListOf<File>()
        var currentDexPool = DexPool(opcodes)

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

    private fun isDesugarLib(classDef: ClassDef): Boolean {
        return DESUGAR_NAMESPACES.any { classDef.type.startsWith(it) }
    }
}