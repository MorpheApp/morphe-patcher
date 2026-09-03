/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import app.morphe.patcher.PatcherResult
import app.morphe.patcher.apk.ApkUtils.applyTo
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ApkUtilsTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `compiled resource APK is used directly as the output base`() {
        val targetApk = temporaryDirectory.resolve("target.apk").also { apk ->
            writeZip(
                apk,
                mapOf(
                    "assets/original-only.txt" to "original".toByteArray(),
                    "res/raw/old.txt" to "old".toByteArray(),
                    "classes.dex" to "original dex".toByteArray(),
                ),
            )
        }
        val resourcesApk = temporaryDirectory.resolve("resources.apk").also { apk ->
            writeZip(
                apk,
                mapOf(
                    "assets/resource-base-only.txt" to "resource base".toByteArray(),
                    "assets/delete-me.txt" to "delete me".toByteArray(),
                    "res/raw/new.txt" to "new".toByteArray(),
                    "classes.dex" to "stale dex".toByteArray(),
                    "classes99.dex" to "stale dex 99".toByteArray(),
                ),
            )
        }
        val otherResources = temporaryDirectory.resolve("other-resources").also { directory ->
            directory.resolve("assets/raw-added.txt").apply {
                parentFile.mkdirs()
                writeText("raw resource")
            }
        }
        val primaryDex = CloseTrackingInputStream("patched dex".toByteArray())
        val secondaryDex = CloseTrackingInputStream("patched dex 2".toByteArray())
        val result = PatcherResult(
            linkedSetOf(
                PatcherResult.PatchedDexFile("classes.dex", primaryDex),
                PatcherResult.PatchedDexFile("classes2.dex", secondaryDex),
            ),
            PatcherResult.PatchedResources(
                resourcesApk,
                otherResources,
                setOf("assets/raw-added.txt"),
                setOf("assets/delete-me.txt"),
            ),
        )

        result.applyTo(targetApk)

        val entries = readZip(targetApk)
        assertFalse("assets/original-only.txt" in entries)
        assertFalse("res/raw/old.txt" in entries)
        assertFalse("assets/delete-me.txt" in entries)
        assertFalse("classes99.dex" in entries)
        assertContentEquals("resource base".toByteArray(), entries["assets/resource-base-only.txt"])
        assertContentEquals("new".toByteArray(), entries["res/raw/new.txt"])
        assertContentEquals("raw resource".toByteArray(), entries["assets/raw-added.txt"])
        assertContentEquals("patched dex".toByteArray(), entries["classes.dex"])
        assertContentEquals("patched dex 2".toByteArray(), entries["classes2.dex"])
        assertTrue(primaryDex.closed)
        assertTrue(secondaryDex.closed)
    }

    @Test
    fun `original APK remains the output base without a compiled resource APK`() {
        val targetApk = temporaryDirectory.resolve("target.apk").also { apk ->
            writeZip(
                apk,
                mapOf(
                    "assets/original-only.txt" to "original".toByteArray(),
                    "assets/delete-me.txt" to "delete me".toByteArray(),
                    "classes.dex" to "original dex".toByteArray(),
                    "classes2.dex" to "untouched dex".toByteArray(),
                ),
            )
        }
        val otherResources = temporaryDirectory.resolve("other-resources").also { directory ->
            directory.resolve("assets/raw-added.txt").apply {
                parentFile.mkdirs()
                writeText("raw resource")
            }
        }
        val primaryDex = CloseTrackingInputStream("patched dex".toByteArray())
        val result = PatcherResult(
            setOf(PatcherResult.PatchedDexFile("classes.dex", primaryDex)),
            PatcherResult.PatchedResources(
                null,
                otherResources,
                emptySet(),
                setOf("assets/delete-me.txt"),
            ),
        )

        result.applyTo(targetApk)

        val entries = readZip(targetApk)
        assertContentEquals("original".toByteArray(), entries["assets/original-only.txt"])
        assertFalse("assets/delete-me.txt" in entries)
        assertContentEquals("raw resource".toByteArray(), entries["assets/raw-added.txt"])
        assertContentEquals("patched dex".toByteArray(), entries["classes.dex"])
        assertContentEquals("untouched dex".toByteArray(), entries["classes2.dex"])
        assertTrue(primaryDex.closed)
    }

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, contents) ->
                output.putNextEntry(ZipEntry(name))
                output.write(contents)
                output.closeEntry()
            }
        }
    }

    private fun readZip(file: File): Map<String, ByteArray> =
        ZipFile(file).use { zip ->
            zip.entries().asSequence().associate { entry ->
                entry.name to zip.getInputStream(entry).use { it.readBytes() }
            }
        }

    private class CloseTrackingInputStream(contents: ByteArray) : ByteArrayInputStream(contents) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
