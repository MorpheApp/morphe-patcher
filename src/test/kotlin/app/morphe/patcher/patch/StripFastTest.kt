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
import com.android.tools.smali.dexlib2.dexbacked.raw.HeaderItem
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.function.Supplier
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class StripFastTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @OptIn(InternalApi::class)
    @Test
    fun `a dex file left without classes is dropped and the others are renumbered`() {
        val apk = temporaryDirectory.resolve("input.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            listOf("classes.dex" to arrayOf("La1;", "La2;"), "classes2.dex" to arrayOf("Lb;")).forEach { (name, types) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(dexOf(*types))
                zip.closeEntry()
            }
        }
        val config = PatcherConfig(apk, temporaryDirectory.resolve("tmp")).apply {
            bytecodeMode = BytecodeMode.STRIP_FAST
            initializeTemporaryFilesDirectories()
        }

        val (dexFiles, complete) = BytecodePatchContext(config, mockk(relaxed = true)).use { context ->
            context.decodeDexFiles()
            context.mutableClassDefBy("La1;")
            context.mutableClassDefBy("La2;")
            context.get().associate { it.name to it.stream.use { stream -> stream.readBytes() } } to context.dexFilesComplete
        }

        assertEquals(setOf("classes.dex", "classes2.dex"), dexFiles.keys)
        assertEquals(setOf("Lb;"), typesIn(dexFiles.getValue("classes.dex")))
        assertEquals(setOf("La1;", "La2;"), typesIn(dexFiles.getValue("classes2.dex")))
        assertTrue(complete)
    }

    @OptIn(InternalApi::class)
    @Test
    fun `a dex file between others left without classes does not leave a gap`() {
        val apk = temporaryDirectory.resolve("input.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            listOf("classes.dex" to "La;", "classes2.dex" to "Lb;", "classes3.dex" to "Lc;").forEach { (name, type) ->
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
            context.mutableClassDefBy("Lb;")
            context.get().associate { it.name to it.stream.use { stream -> stream.readBytes() } }
        }

        assertEquals(
            mapOf("classes.dex" to setOf("La;"), "classes2.dex" to setOf("Lc;"), "classes3.dex" to setOf("Lb;")),
            dexFiles.mapValues { (_, dex) -> typesIn(dex) },
        )
    }

    @OptIn(InternalApi::class)
    @Test
    fun `only the stripped and new dex files are output, numbered after the originals`() {
        val apk = temporaryDirectory.resolve("input.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            listOf("classes.dex" to arrayOf("La;", "Lc;"), "classes2.dex" to arrayOf("Lb;")).forEach { (name, types) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(dexOf(*types))
                zip.closeEntry()
            }
        }
        val config = PatcherConfig(apk, temporaryDirectory.resolve("tmp")).apply {
            bytecodeMode = BytecodeMode.STRIP_FAST
            initializeTemporaryFilesDirectories()
        }

        val (dexFiles, complete) = BytecodePatchContext(config, mockk(relaxed = true)).use { context ->
            context.decodeDexFiles()
            context.mutableClassDefBy("La;")
            context.get().associate { it.name to it.stream.use { stream -> stream.readBytes() } } to context.dexFilesComplete
        }

        assertEquals(setOf("classes.dex", "classes3.dex"), dexFiles.keys)
        assertEquals(setOf("Lc;"), typesIn(dexFiles.getValue("classes.dex")))
        assertEquals(setOf("La;"), typesIn(dexFiles.getValue("classes3.dex")))
        assertFalse(complete)
    }

    @OptIn(InternalApi::class)
    @Test
    fun `unchanged extension classes are output in a copy of their extension dex file`() {
        val apk = temporaryDirectory.resolve("input.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dexOf("La;"))
            zip.closeEntry()
        }
        val config = PatcherConfig(apk, temporaryDirectory.resolve("tmp")).apply {
            bytecodeMode = BytecodeMode.STRIP_FAST
            initializeTemporaryFilesDirectories()
        }
        // A newer dex version than the app, as extensions often are.
        val extension = dexOf("Lext/A;", "Lext/B;", "Lext/C;", opcodes = Opcodes.forApi(27))
        val patch = bytecodePatch { extendWith(Supplier { extension.inputStream() }) }

        val dexFiles = BytecodePatchContext(config, mockk(relaxed = true)).use { context ->
            context.decodeDexFiles()
            context.mergeExtension(patch)
            context.mutableClassDefBy("Lext/B;")
            context.get().associate { it.name to it.stream.use { stream -> stream.readBytes() } }
        }

        assertEquals(setOf("classes2.dex", "classes3.dex"), dexFiles.keys)
        assertEquals(setOf("Lext/B;"), typesIn(dexFiles.getValue("classes2.dex")))
        assertEquals(setOf("Lext/A;", "Lext/C;"), typesIn(dexFiles.getValue("classes3.dex")))
        assertEquals(
            HeaderItem.getVersion(dexFiles.getValue("classes2.dex"), 0),
            HeaderItem.getVersion(dexFiles.getValue("classes3.dex"), 0),
            "The copy has the dex version of the written classes",
        )
    }

    private fun dexOf(vararg types: String, opcodes: Opcodes = Opcodes.getDefault()): ByteArray {
        val file = temporaryDirectory.resolve("${types.first().trim('L', ';').replace('/', '_')}.dex")
        DexPool(opcodes).apply {
            types.forEach { type ->
                internClass(ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null))
            }
            writeTo(FileDataStore(file))
        }
        return file.readBytes()
    }

    private fun typesIn(dex: ByteArray) =
        DexBackedDexFile.fromInputStream(null, dex.inputStream().buffered()).classes.mapTo(HashSet()) { it.type }
}
