/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.resource.coder.ResourceTestApk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ObbFilesTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `patches see the OBB files the config was given`() {
        val obbFile = temporaryDirectory.resolve("main.1.com.test.app.obb").apply {
            writeBytes(ByteArray(16) { it.toByte() })
        }
        val obbFiles = mapOf(OBB_PATH to obbFile)

        patcher(obbFiles).use { patcher ->
            val metadata = patcher.context.packageMetadata

            assertEquals(obbFiles, metadata.obbFiles)
            assertEquals(obbFile, metadata.obbFiles.getValue(OBB_PATH))
        }
    }

    @Test
    fun `a package patched from a plain APK has no OBB files`() {
        patcher(emptyMap()).use { patcher ->
            assertTrue(patcher.context.packageMetadata.obbFiles.isEmpty())
        }
    }

    @Test
    fun `a patch can copy an OBB into the APK`() {
        val obbBytes = ByteArray(4096) { it.toByte() }
        val obbFile = temporaryDirectory.resolve("main.1.com.test.app.obb").apply { writeBytes(obbBytes) }
        val embedObb = patch("Embed OBB") {
            execute {
                val obb = packageMetadata.obbFiles.getValue(OBB_PATH)
                obb.copyTo(resourceFile("assets/main.obb", false), overwrite = true)
            }
        }
        val output = temporaryDirectory.resolve("patched.apk")

        patcher(mapOf(OBB_PATH to obbFile)).use { patcher ->
            patcher += setOf(embedObb)
            runBlocking { patcher().toList() }.forEach { assertNull(it.exception, it.exception?.stackTraceToString()) }
            patcher.writeApk(output)
        }

        ZipFile(output).use { zip ->
            val entry = assertNotNull(zip.getEntry("assets/main.obb"))
            assertContentEquals(obbBytes, zip.getInputStream(entry).use { it.readBytes() })
        }
        assertContentEquals(obbBytes, obbFile.readBytes(), "the OBB the caller passed was changed")
    }

    @Test
    fun `patches see a read-only copy of the map the config was given`() {
        val obbFile = temporaryDirectory.resolve("main.1.com.test.app.obb").apply { writeText("obb") }
        val obbFiles = mutableMapOf(OBB_PATH to obbFile)

        patcher(obbFiles).use { patcher ->
            val metadata = patcher.context.packageMetadata
            obbFiles.clear()

            assertEquals(mapOf(OBB_PATH to obbFile), metadata.obbFiles)
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (metadata.obbFiles as MutableMap<String, File>).clear()
            }
        }
    }

    private fun patcher(obbFiles: Map<String, File>): Patcher {
        val apk = ResourceTestApk.build(temporaryDirectory)
        return Patcher(
            PatcherConfig(
                apkFile = apk,
                obbFiles = obbFiles,
                temporaryFilesPath = temporaryDirectory.resolve("temporary-files"),
            ),
        )
    }

    private companion object {
        const val OBB_PATH = "Android/obb/com.test.app/main.1.com.test.app.obb"
    }
}
