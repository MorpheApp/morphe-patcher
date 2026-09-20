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
import kotlin.test.assertEquals
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

    @Test
    fun `an entry deleted and then recreated keeps the recreated content`() {
        val targetApk = temporaryDirectory.resolve("target.apk").also { apk ->
            writeZip(apk, mapOf("assets/data.bin" to "original".toByteArray()))
        }
        val otherResources = temporaryDirectory.resolve("other-resources").also { directory ->
            directory.resolve("assets/data.bin").apply {
                parentFile.mkdirs()
                writeText("recreated")
            }
        }
        val result = PatcherResult(
            emptySet(),
            PatcherResult.PatchedResources(null, otherResources, emptySet(), setOf("assets/data.bin")),
        )

        result.applyTo(targetApk)

        assertContentEquals("recreated".toByteArray(), readZip(targetApk)["assets/data.bin"])
    }

    @Test
    fun `input dex files survive when no bytecode was patched`() {
        val resourcesApk = temporaryDirectory.resolve("resources.apk").also { apk ->
            writeZip(
                apk,
                mapOf(
                    "resources.arsc" to "table".toByteArray(),
                    "classes.dex" to "original dex".toByteArray(),
                    "classes2.dex" to "original dex 2".toByteArray(),
                ),
            )
        }
        val targetApk = temporaryDirectory.resolve("target.apk")
        val result = PatcherResult(
            emptySet(),
            PatcherResult.PatchedResources(resourcesApk, null, emptySet(), emptySet()),
        )

        result.applyTo(targetApk)

        val entries = readZip(targetApk)
        assertContentEquals("original dex".toByteArray(), entries["classes.dex"])
        assertContentEquals("original dex 2".toByteArray(), entries["classes2.dex"])
        assertContentEquals("table".toByteArray(), entries["resources.arsc"])
    }

    @Test
    fun `an entry written with a data descriptor keeps its local and central headers consistent`() {
        // ZipOutputStream writes deflated entries with a data descriptor (general purpose bit 3),
        // as the build tools of about 2012 did. The first entry is not moved when the one after
        // it is deleted, so only its central directory header gets rewritten.
        val payload = ByteArray(300) { it.toByte() }
        val targetApk = temporaryDirectory.resolve("target.apk").also { apk ->
            writeZip(apk, mapOf("res/raw/first.dat" to payload, "assets/second.bin" to "second".toByteArray()))
        }
        assertTrue(generalPurposeFlags(targetApk).getValue("res/raw/first.dat").first and 8 != 0, "precondition")

        val result = PatcherResult(
            emptySet(),
            PatcherResult.PatchedResources(null, null, emptySet(), setOf("assets/second.bin")),
        )
        result.applyTo(targetApk)

        generalPurposeFlags(targetApk).forEach { (name, flags) ->
            assertEquals(flags.first and 8, flags.second and 8, "$name: data descriptor bit, local vs central header")
        }
        assertContentEquals(payload, readZip(targetApk)["res/raw/first.dat"])
    }

    /** Entry name to (local header flags, central directory flags), read straight from the bytes. */
    private fun generalPurposeFlags(file: File): Map<String, Pair<Int, Int>> {
        val bytes = file.readBytes()
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val eocd = (bytes.size - 22 downTo 0).first { buffer.getInt(it) == 0x06054b50 }
        var offset = buffer.getInt(eocd + 16)
        val flags = mutableMapOf<String, Pair<Int, Int>>()
        repeat(buffer.getShort(eocd + 10).toInt() and 0xFFFF) {
            val nameLength = buffer.getShort(offset + 28).toInt() and 0xFFFF
            val extraLength = buffer.getShort(offset + 30).toInt() and 0xFFFF
            val commentLength = buffer.getShort(offset + 32).toInt() and 0xFFFF
            val name = String(bytes, offset + 46, nameLength, Charsets.UTF_8)
            val localHeader = buffer.getInt(offset + 42)
            flags[name] = (buffer.getShort(localHeader + 6).toInt() and 0xFFFF) to
                (buffer.getShort(offset + 8).toInt() and 0xFFFF)
            offset += 46 + nameLength + extraLength + commentLength
        }
        return flags
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
