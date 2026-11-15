/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import app.morphe.patcher.logging.LogLevel
import app.morphe.patcher.logging.Logger
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ApkMergerTest {
    @TempDir
    lateinit var temporaryDirectory: File

    private val bundler = ApkMerger()

    @Test
    fun `extracts the OBB files of a bundle under the path they have inside it`() {
        val obb = ByteArray(4096) { it.toByte() }
        val bundle = writeBundle(
            "Android/obb/com.example/game/main.1.com.example.obb" to obb,
        )

        val files = bundler.extractObbFiles(bundle, temporaryDirectory.resolve("out"))

        assertEquals(listOf("Android/obb/com.example/game/main.1.com.example.obb"), files.keys.toList())
        assertContentEquals(obb, files.values.single().readBytes())
    }

    @Test
    fun `extracts every OBB file of a bundle`() {
        val bundle = writeBundle(
            "Android/obb/com.example/main.1.com.example.obb" to "one".toByteArray(),
            "Android/obb/com.example/patch.2.com.example.obb" to "two".toByteArray(),
        )

        val files = bundler.extractObbFiles(bundle, temporaryDirectory.resolve("out"))

        assertEquals(
            mapOf(
                "Android/obb/com.example/main.1.com.example.obb" to "one",
                "Android/obb/com.example/patch.2.com.example.obb" to "two",
            ),
            files.mapValues { it.value.readText() },
        )
    }

    @Test
    fun `ignores the APK modules and the other files of a bundle`() {
        val bundle = writeBundle(
            "base.apk" to "apk".toByteArray(),
            "config.arm64_v8a.apk" to "split".toByteArray(),
            "manifest.json" to "{}".toByteArray(),
            "icon.png" to "png".toByteArray(),
            "Android/data/com.example/files/save.dat" to "save".toByteArray(),
            "Android/obb/com.example/main.1.com.example.obb" to "obb".toByteArray(),
        )

        val files = bundler.extractObbFiles(bundle, temporaryDirectory.resolve("out"))

        assertEquals(listOf("Android/obb/com.example/main.1.com.example.obb"), files.keys.toList())
        assertFalse(temporaryDirectory.resolve("out/base.apk").exists())
        assertFalse(temporaryDirectory.resolve("out/Android/data").exists())
    }

    @Test
    fun `returns no files for a plain APK`() {
        val apk = writeBundle("assets/data.bin" to "data".toByteArray())
        val outputDir = temporaryDirectory.resolve("out")

        val files = bundler.extractObbFiles(apk, outputDir)

        assertTrue(files.isEmpty())
        assertFalse(outputDir.exists())
    }

    @Test
    fun `throws for a missing bundle`() {
        assertFailsWith<FileNotFoundException> {
            bundler.extractObbFiles(
                temporaryDirectory.resolve("missing.xapk"),
                temporaryDirectory.resolve("out"),
            )
        }
    }

    @Test
    fun `extracts an OBB the bundle keeps outside Android obb`() {
        val bundle = writeBundle(
            "base.apk" to "apk".toByteArray(),
            "game.obb" to "obb".toByteArray(),
            "extra/data.obb" to "nested".toByteArray(),
        )

        val files = bundler.extractObbFiles(bundle, temporaryDirectory.resolve("out"))

        assertEquals(setOf("game.obb", "extra/data.obb"), files.keys)
        assertEquals("nested", files.getValue("extra/data.obb").readText())
    }

    @Test
    fun `extracts an OBB regardless of the case of its extension`() {
        val bundle = writeBundle("Android/obb/com.example/MAIN.OBB" to "obb".toByteArray())

        val files = bundler.extractObbFiles(bundle, temporaryDirectory.resolve("out"))

        assertEquals(listOf("Android/obb/com.example/MAIN.OBB"), files.keys.toList())
    }

    @Test
    fun `extracts a file under Android obb that has no OBB extension`() {
        val bundle = writeBundle("Android/obb/com.example/main.1.com.example.bin" to "obb".toByteArray())

        val files = bundler.extractObbFiles(bundle, temporaryDirectory.resolve("out"))

        assertEquals(listOf("Android/obb/com.example/main.1.com.example.bin"), files.keys.toList())
    }

    @Test
    fun `does not extract OBBs that escape the output directory`() {
        val caseDir = temporaryDirectory.resolve("case").also { it.mkdirs() }
        val outputDir = caseDir.resolve("out")
        val bundle = writeBundle(
            "../escaped.obb" to "a".toByteArray(),
            "nested/../../escaped-two.obb" to "b".toByteArray(),
            caseDir.resolve("absolute.obb").absolutePath to "c".toByteArray(),
            "Android/obb/com.example/ok.obb" to "d".toByteArray(),
        )

        val files = bundler.extractObbFiles(bundle, outputDir)

        assertEquals(listOf("Android/obb/com.example/ok.obb"), files.keys.toList())
        val outputRoot = outputDir.canonicalFile.toPath()
        files.values.forEach { file ->
            assertTrue(
                file.canonicalFile.toPath().startsWith(outputRoot),
                "escaped to ${file.canonicalPath}",
            )
        }
        assertFalse(caseDir.resolve("escaped.obb").exists(), "wrote outside the output directory")
        assertFalse(caseDir.resolve("escaped-two.obb").exists(), "wrote outside the output directory")
        assertFalse(caseDir.resolve("absolute.obb").exists(), "wrote outside the output directory")
    }

    @Test
    fun `logs the OBBs it skips for their path`() {
        val bundle = writeBundle(
            "../escaped.obb" to "a".toByteArray(),
            "Android/obb/com.example/ok.obb" to "b".toByteArray(),
        )
        val logger = RecordingLogger()

        ApkMerger(logger).extractObbFiles(bundle, temporaryDirectory.resolve("out"))

        assertEquals(
            listOf(LogLevel.WARN to "Skipping OBB file with a path outside the output directory: ../escaped.obb"),
            logger.messages.filter { it.first == LogLevel.WARN },
        )
    }

    private class RecordingLogger : Logger() {
        val messages = mutableListOf<Pair<LogLevel, String>>()

        override fun log(level: LogLevel, message: String) {
            messages += level to message
        }
    }

    private fun writeBundle(vararg entries: Pair<String, ByteArray>): File {
        val bundle = temporaryDirectory.resolve("bundle-${System.nanoTime()}.xapk")
        ZipOutputStream(bundle.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bundle
    }
}
