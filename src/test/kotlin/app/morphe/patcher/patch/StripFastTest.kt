/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

import app.morphe.patcher.InternalApi
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.dex.BytecodeMode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

internal class StripFastTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @OptIn(InternalApi::class)
    @Test
    fun `only the stripped and new dex files are output, numbered after the originals`() {
        val apk = temporaryDirectory.resolve("input.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            listOf("classes.dex" to "La;", "classes2.dex" to "Lb;").forEach { (name, type) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(dexOf(type))
                zip.closeEntry()
            }
        }
        val config = PatcherConfig(apk, temporaryDirectory.resolve("tmp")).apply {
            bytecodeMode = BytecodeMode.STRIP_FAST
            initializeTemporaryFilesDirectories()
        }

        val dexFiles = BytecodePatchContext(config, mockk(relaxed = true)).use { context ->
            context.decodeDexFiles()
            context.mutableClassDefBy("La;")
            context.get().associate { it.name to it.stream.use { stream -> stream.readBytes() } }
        }

        assertEquals(setOf("classes.dex", "classes3.dex"), dexFiles.keys)
        assertEquals(emptySet(), typesIn(dexFiles.getValue("classes.dex")))
        assertEquals(setOf("La;"), typesIn(dexFiles.getValue("classes3.dex")))
    }

    private fun dexOf(type: String): ByteArray {
        val file = temporaryDirectory.resolve("${type.trim('L', ';')}.dex")
        DexPool(Opcodes.getDefault()).apply {
            internClass(ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null))
            writeTo(FileDataStore(file))
        }
        return file.readBytes()
    }

    private fun typesIn(dex: ByteArray) =
        DexBackedDexFile.fromInputStream(null, dex.inputStream().buffered()).classes.mapTo(HashSet()) { it.type }
}
