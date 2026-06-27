package app.morphe.patcher.dex

import app.morphe.patcher.environment.EnvironmentUtils
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * The result of reading a multidex file, containing both the merged [DexFile] view
 * and the names of each individual DEX entry within the container.
 *
 * @param extractedDexFiles The names of each original DEX entry (e.g., "classes.dex", "classes2.dex")
 */
internal class MultidexReadResult(
    val extractedDexFiles: List<File>
) : Closeable {
    val memoryMappedDexFiles = extractedDexFiles.map { file ->
        MemoryBackedDexFile(RandomAccessFile(file, "rw").channel)
    }

    val dexFile = object : DexFile {
        private val _opcodes: Opcodes by lazy {
            memoryMappedDexFiles.maxByOrNull { it.opcodes.api }!!.opcodes
        }
        override fun getOpcodes(): Opcodes { return _opcodes }

        override fun getClasses(): Set<ClassDef> {
            return memoryMappedDexFiles.flatMap { it.classes }.toSet()
        }
    }

    private val entryNames = extractedDexFiles.map { file -> file.name }
    // Track which class descriptors belong to which DEX entry.
    val classDescriptorsByEntry = entryNames.zip(memoryMappedDexFiles).associate { (name, dex) ->
        name to dex.classes.let { classes ->
            classes.mapTo(HashSet(2 * classes.size)) { it.type }
        }
    }

    override fun close() {
        if (EnvironmentUtils.isWindowsEnvironment)
            memoryMappedDexFiles.forEach { it.close() }
    }
}