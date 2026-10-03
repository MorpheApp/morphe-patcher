/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import app.morphe.patcher.patch.PatchException
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class DexReadWriteTest {
    @TempDir
    lateinit var temporaryDirectory: File

    private fun zipOf(vararg entries: Pair<String, ByteArray>) =
        temporaryDirectory.resolve("input.apk").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun `dex entry that climbs out of the output directory is rejected`() {
        val apk = zipOf("classes/../../evil.dex" to "payload".toByteArray())
        val outputDir = temporaryDirectory.resolve("out")

        assertFailsWith<SecurityException> { DexReadWrite.extractDexEntries(apk, outputDir) }
        assertFalse(temporaryDirectory.resolve("evil.dex").exists())
    }

    @Test
    fun `ordinary dex entries are extracted unchanged`() {
        val apk = zipOf(
            "classes.dex" to "one".toByteArray(),
            "classes2.dex" to "two".toByteArray(),
            "assets/readme.txt" to "ignored".toByteArray(),
        )
        val outputDir = temporaryDirectory.resolve("out")

        val files = DexReadWrite.extractDexEntries(apk, outputDir)

        assertEquals(listOf("classes.dex", "classes2.dex"), files.map { it.name })
        assertEquals("two", outputDir.resolve("classes2.dex").readText())
    }

    @Test
    fun `apk without dex files fails with a clear message`() {
        val apk = zipOf("assets/readme.txt" to "nothing to patch".toByteArray())

        val exception = assertFailsWith<PatchException> {
            DexReadWrite.readMultidexFileFromZip(apk, temporaryDirectory.resolve("out"))
        }
        assertEquals("APK contains no DEX files to patch", exception.message)
    }
}
