/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.resource.coder.ResourceTestApk
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
