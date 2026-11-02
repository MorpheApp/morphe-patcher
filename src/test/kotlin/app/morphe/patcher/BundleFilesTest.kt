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

internal class BundleFilesTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `patches see the bundle files the config was given`() {
        val bundleFile = temporaryDirectory.resolve("main.1.com.test.app.obb").apply {
            writeBytes(ByteArray(16) { it.toByte() })
        }
        val bundleFiles = mapOf(BUNDLE_PATH to bundleFile)

        patcher(bundleFiles).use { patcher ->
            val metadata = patcher.context.packageMetadata

            assertEquals(bundleFiles, metadata.bundleFiles)
            assertEquals(bundleFile, metadata.bundleFiles.getValue(BUNDLE_PATH))
        }
    }

    @Test
    fun `a package patched from a plain APK has no bundle files`() {
        patcher(emptyMap()).use { patcher ->
            assertTrue(patcher.context.packageMetadata.bundleFiles.isEmpty())
        }
    }

    private fun patcher(bundleFiles: Map<String, File>): Patcher {
        val apk = ResourceTestApk.build(temporaryDirectory)
        return Patcher(
            PatcherConfig(
                apkFile = apk,
                bundleFiles = bundleFiles,
                temporaryFilesPath = temporaryDirectory.resolve("temporary-files"),
            ),
        )
    }

    private companion object {
        const val BUNDLE_PATH = "Android/obb/com.test.app/main.1.com.test.app.obb"
    }
}
