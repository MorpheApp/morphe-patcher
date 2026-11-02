/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.apk.CompressedDexFiles
import app.morphe.patcher.apk.overlapDexCompression
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.logging.PhaseTimings
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchBundleInfo
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.CpuArchitecture
import app.morphe.patcher.resource.coder.FULL_RESOURCE_ENCODE_PROPERTY
import app.morphe.patcher.resource.coder.ResourceTestApk
import com.android.tools.build.apkzlib.zip.ZFile
import com.reandroid.arsc.chunk.TableBlock
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.logging.Logger
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Writes the patched APK with [Patcher.writeApk] and compares it with what [Patcher.get] followed by
 * [ApkUtils.applyTo] writes for the same input and patches, as hosts apply it.
 */
internal class WriteApkTest {
    @TempDir
    lateinit var tempDir: File

    private data class Entry(val crc: Long, val size: Long, val method: Int)

    private class Config(
        val keepArchitectures: Set<CpuArchitecture> = emptySet(),
        val bytecodeMode: BytecodeMode = BytecodeMode.STRIP_FAST,
    )

    // region Scenarios

    @ParameterizedTest
    @ValueSource(strings = ["on request", "up front", "full rebuild"])
    fun `a resource patch writes what applyTo writes`(decoding: String) {
        val input = inputApk {
            add("assets/recreate.txt", "original ".repeat(50))
            add("assets/tiny.txt", "x")
            add("assets/delete.txt", "delete ".repeat(50))
            addStored("lib/arm64-v8a/libfoo.so", ByteArray(5000) { it.toByte() })
            addStored("lib/x86/libfoo.so", ByteArray(3000) { it.toByte() })
            add("lib/x86/libcompressed.so", "compressed library ".repeat(100))
            add("META-INF/CERT.SF", "signature file")
            add("META-INF/MANIFEST.MF", "manifest")
        }
        val patches = {
            val patch = resourcePatch("Change resources") {
                execute {
                    document("res/values/strings.xml").use { document ->
                        val strings = document.getElementsByTagName("string")
                        for (index in 0 until strings.length) {
                            val string = strings.item(index)
                            if (string.attributes.getNamedItem("name").nodeValue == "hello") string.textContent = "Patched"
                        }
                    }
                    get("res/drawable/added.png", false).apply {
                        parentFile.mkdirs()
                        writeBytes(ByteArray(2000) { (it % 7).toByte() })
                    }
                    delete("res/drawable-xxhdpi/icon.png")
                    get("assets/added.txt").writeText("added ".repeat(100))
                    get("assets/added-tiny.txt").writeText("y")
                    delete("assets/delete.txt")
                    delete("assets/recreate.txt")
                    get("assets/recreate.txt").writeText("recreated ".repeat(40))
                }
            }
            setOf(if (decoding == "up front") patch.builtFor("1.15.1") else patch.builtFor(ON_REQUEST))
        }
        val config = Config(keepArchitectures = setOf(CpuArchitecture.ARM64_V8A))

        val (applied, written) = withFullRebuild(decoding == "full rebuild") { patchBothWays(input, config, patches) }

        val entries = assertSameApk(applied, written)
        assertTrue("res/drawable-xxhdpi/icon.png" !in entries)
        assertTrue("assets/delete.txt" !in entries)
        assertTrue("lib/x86/libfoo.so" !in entries)
        assertTrue("lib/arm64-v8a/libfoo.so" in entries)
        assertTrue("META-INF/CERT.SF" !in entries && "META-INF/MANIFEST.MF" !in entries)
        assertContentEquals("recreated ".repeat(40).toByteArray(), read(written, "assets/recreate.txt"))
        assertContentEquals("added ".repeat(100).toByteArray(), read(written, "assets/added.txt"))
        assertSigns(written)
    }

    @ParameterizedTest
    @ValueSource(strings = ["on request", "up front", "full rebuild"])
    fun `patching again in the same process compiles the same resources`(decoding: String) {
        val input = inputApk {}
        // The same size as the file it replaces when decoded on request, where an edit within one tick of
        // the file time is told apart by the content. Longer when decoded up front, where only metadata is compared.
        val patched = if (decoding == "up front") "Patched by a patch" else "Patched"
        val patches = {
            val patch = resourcePatch("Change a string") {
                execute {
                    document("res/values/strings.xml").use { document ->
                        val strings = document.getElementsByTagName("string")
                        for (index in 0 until strings.length) {
                            val string = strings.item(index)
                            if (string.attributes.getNamedItem("name").nodeValue == "hello") string.textContent = patched
                        }
                    }
                }
            }
            setOf(if (decoding == "up front") patch.builtFor("1.15.1") else patch.builtFor(ON_REQUEST))
        }

        val tables = withFullRebuild(decoding == "full rebuild") {
            listOf("first", "second").map { name ->
                val result = patcher(input, name, Config(), patches()).use { it.get() }
                read(assertNotNull(result.resources.resourcesApk), "resources.arsc")
            }
        }

        assertContentEquals(tables[0], tables[1], "the second run compiles what the first one does")
        val packageBlock = TableBlock.load(ByteArrayInputStream(tables[1])).listPackages().single()
        assertEquals(patched, packageBlock.getEntry("", "string", "hello").resValue.valueAsString)
        // Decoded as the first run of a process decodes it, not with the string decoder of the encoder.
        assertEquals("It's \"quoted\"", packageBlock.getEntry("", "string", "escaped").resValue.valueAsString)
    }

    @Test
    fun `a raw resource patch writes what applyTo writes and copies the table and manifest`() {
        val input = inputApk {
            add("assets/recreate.txt", "original ".repeat(50))
            add("assets/delete.txt", "delete ".repeat(50))
            addStored("lib/arm64-v8a/libfoo.so", ByteArray(5000) { it.toByte() })
        }
        val patches = {
            setOf(
                rawResourcePatch("Change raw files") {
                    execute {
                        get("assets/added.txt").writeText("added ".repeat(100))
                        get("assets/added-tiny.txt").writeText("y")
                        delete("assets/delete.txt")
                        delete("assets/recreate.txt")
                        get("assets/recreate.txt").writeText("recreated ".repeat(40))
                    }
                },
            )
        }

        val (applied, written) = patchBothWays(input, Config(), patches)

        val entries = assertSameApk(applied, written)
        assertCopiedAsTheyAre(input, written, "resources.arsc", "AndroidManifest.xml")
        assertTrue("assets/delete.txt" !in entries)
        assertContentEquals("recreated ".repeat(40).toByteArray(), read(written, "assets/recreate.txt"))
        assertSigns(written)
    }

    @ParameterizedTest
    @ValueSource(strings = ["STRIP_FAST", "FULL"])
    fun `a bytecode patch writes what applyTo writes`(bytecodeMode: String) {
        val input = inputApk {
            add("classes.dex", dexOf("La;", "Lc;"))
            add("classes2.dex", dexOf("Lb;"))
            addStored("lib/arm64-v8a/libfoo.so", ByteArray(5000) { it.toByte() })
            addStored("lib/x86/libfoo.so", ByteArray(3000) { it.toByte() })
        }
        val patches = { setOf(bytecodePatch("Change a class") { execute { mutableClassDefBy("La;") } }) }
        val config = Config(setOf(CpuArchitecture.ARM64_V8A), BytecodeMode.valueOf(bytecodeMode))

        val (applied, written) = patchBothWays(input, config, patches)

        val entries = assertSameApk(applied, written)
        assertCopiedAsTheyAre(input, written, "resources.arsc", "AndroidManifest.xml")
        assertTrue("lib/x86/libfoo.so" !in entries)
        if (bytecodeMode == "STRIP_FAST") {
            // A partial set: the stripped original, the new file and the untouched original.
            assertEquals(setOf("classes.dex", "classes2.dex", "classes3.dex"), entries.keys.filter { it.endsWith(".dex") }.toSet())
            assertEquals(setOf("La;"), typesIn(read(written, "classes3.dex")))
            assertEquals(entryOf(input, "classes2.dex"), entries["classes2.dex"])
        }
        assertSigns(written)
    }

    @Test
    fun `a dex file the input stores is stored as applyTo stores it`() {
        val input = inputApk {
            addStored("classes.dex", dexOf("La;", "Lb;"))
        }
        val patches = { setOf(bytecodePatch("Change a class") { execute { mutableClassDefBy("La;") } }) }

        val (applied, written) = patchBothWays(input, Config(bytecodeMode = BytecodeMode.FULL), patches)

        val entries = assertSameApk(applied, written)
        assertEquals(ZipEntry.STORED, entries.getValue("classes.dex").method)
        assertSigns(applied)
        assertSigns(written)
    }

    @Test
    fun `stripping native libraries without patches writes what applyTo writes`() {
        val input = inputApk {
            add("classes.dex", dexOf("La;"))
            addStored("lib/arm64-v8a/libfoo.so", ByteArray(5000) { it.toByte() })
            addStored("lib/x86/libfoo.so", ByteArray(3000) { it.toByte() })
            add("lib/x86_64/libfoo.so", "compressed library ".repeat(100))
        }

        val (applied, written) = patchBothWays(input, Config(setOf(CpuArchitecture.ARM64_V8A))) { emptySet() }

        val entries = assertSameApk(applied, written)
        assertEquals(setOf("lib/arm64-v8a/libfoo.so"), entries.keys.filter { it.startsWith("lib/") }.toSet())
        assertCopiedAsTheyAre(input, written, "resources.arsc", "AndroidManifest.xml", "classes.dex")
        assertSigns(written)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NONE", "RAW_ONLY", "FULL"])
    fun `an input written with data descriptors writes what applyTo writes`(resourceMode: String) {
        val input = withDataDescriptors(
            inputApk {
                add("assets/delete.txt", "delete ".repeat(50))
                add("assets/kept.txt", "kept ".repeat(50))
                add("classes.dex", dexOf("La;"))
            },
        )
        assertTrue(generalPurposeFlags(input).getValue("assets/kept.txt").first and 8 != 0, "precondition")
        val patches: () -> Set<Patch<*>> = {
            when (resourceMode) {
                "NONE" -> setOf(bytecodePatch("Change a class") { execute { mutableClassDefBy("La;") } })
                "RAW_ONLY" -> setOf(rawResourcePatch("Delete") { execute { delete("assets/delete.txt") } })
                else -> setOf(resourcePatch("Delete") { execute { delete("assets/delete.txt") } }.builtFor(ON_REQUEST))
            }
        }

        val (applied, written) = patchBothWays(input, Config(), patches)

        assertSameApk(applied, written)
        generalPurposeFlags(written).forEach { (name, flags) ->
            assertEquals(flags.first and 8, flags.second and 8, "$name: data descriptor bit, local vs central header")
        }
        assertContentEquals("kept ".repeat(50).toByteArray(), read(written, "assets/kept.txt"))
        assertSigns(written)
    }

    @Test
    fun `the input APK cannot be written over`() {
        val input = inputApk { }

        Patcher(PatcherConfig(input, tempDir.resolve("patcher"))).use { patcher ->
            patcher += emptySet()
            runBlocking { patcher().toList() }
            assertFailsWith<IllegalArgumentException> {
                patcher.writeApk(File(input.parentFile, "../${input.parentFile.name}/./${input.name}"))
            }
        }
        assertTrue(input.isFile)
    }

    @Test
    fun `compressing the dex files after the resources are encoded writes what overlapping it writes`() {
        val input = inputApk {
            add("classes.dex", dexOf("La;", "Lc;"))
            addStored("classes2.dex", dexOf("Lb;"))
        }
        val patches = {
            setOf(
                bytecodePatch("Change a class") { execute { mutableClassDefBy("La;") } },
                resourcePatch("Add a file") { execute { get("assets/added.txt").writeText("added ".repeat(100)) } }
                    .builtFor(ON_REQUEST),
            )
        }
        val config = Config(bytecodeMode = BytecodeMode.FULL)

        val overlapping = tempDir.resolve("overlapping.apk")
        patcher(input, "overlapping", config, patches()).use { it.writeApk(overlapping) }
        val deferred = tempDir.resolve("deferred.apk")
        withoutOverlap { patcher(input, "deferred", config, patches()).use { it.writeApk(deferred) } }

        assertTrue(overlapDexCompression, "the switch is restored")
        assertFalse(tempDir.resolve("deferred/patched/dex.zip").exists(), "the temporary DEX archive is deleted")
        assertEquals(entriesOf(overlapping), entriesOf(deferred))
        assertContentEquals(overlapping.readBytes(), deferred.readBytes())
        assertAligned(deferred)
        assertSigns(deferred)
    }

    @Test
    fun `deferred compression does not start before await and close cleans up without it`() {
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw AssertionError("read before await")
            override fun close() {
                closed = true
            }
        }
        val archive = tempDir.resolve("patched/dex.zip").apply { parentFile.mkdirs(); writeText("stale") }
        val timings = PhaseTimings(Logger.getLogger(WriteApkTest::class.java.name), "test")

        CompressedDexFiles(
            listOf(PatcherResult.PatchedDexFile("classes.dex", stream)),
            archive,
            tempDir.resolve("missing.apk"),
            timings,
            overlap = false,
        ).close()

        assertTrue(closed, "the stream is closed")
        assertFalse(archive.exists(), "the temporary archive is deleted")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a dex file that cannot be read fails writing and leaves no temporary archive`(overlap: Boolean) {
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw IOException("unreadable")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("unreadable")
            override fun close() {
                closed = true
            }
        }
        val archive = tempDir.resolve("patched/dex.zip")
        val timings = PhaseTimings(Logger.getLogger(WriteApkTest::class.java.name), "test")

        val failure = CompressedDexFiles(
            listOf(PatcherResult.PatchedDexFile("classes.dex", stream)),
            archive,
            tempDir.resolve("missing.apk"),
            timings,
            overlap,
        ).use { dexFiles -> assertFails { dexFiles.await() } }

        assertTrue(generateSequence(failure) { it.cause }.any { it.message == "unreadable" }, failure.toString())
        assertTrue(closed, "the stream is closed")
        assertFalse(archive.exists(), "the temporary archive is deleted")
    }

    // endregion

    // region Running the patcher

    private fun ResourcePatch.builtFor(patcherVersion: String) = apply {
        bundle = PatchBundleInfo(File("Patches.mpp"), "Patches", "1.0.0", patcherVersion)
    }

    private fun <T> withFullRebuild(fullRebuild: Boolean, block: () -> T): T {
        if (!fullRebuild) return block()
        System.setProperty(FULL_RESOURCE_ENCODE_PROPERTY, "true")
        try {
            return block()
        } finally {
            System.clearProperty(FULL_RESOURCE_ENCODE_PROPERTY)
        }
    }

    private fun <T> withoutOverlap(block: () -> T): T {
        val previous = overlapDexCompression
        overlapDexCompression = false
        try {
            return block()
        } finally {
            overlapDexCompression = previous
        }
    }

    private fun patcher(input: File, name: String, config: Config, patches: Set<Patch<*>>): Patcher {
        return Patcher(
            PatcherConfig(
                input,
                tempDir.resolve(name),
                keepArchitectures = config.keepArchitectures,
                useBytecodeMode = config.bytecodeMode,
            ),
        ).also { patcher ->
            patcher += patches
            runBlocking { patcher().toList() }.forEach { assertNull(it.exception, it.exception?.stackTraceToString()) }
        }
    }

    /**
     * Patches [input] with [get][Patcher.get] and [applyTo] as hosts do, and with [Patcher.writeApk].
     *
     * @return The APK of each.
     */
    private fun patchBothWays(input: File, config: Config, patches: () -> Set<Patch<*>>): Pair<File, File> {
        val applied = tempDir.resolve("applied.apk")
        val result = patcher(input, "a", config, patches()).use { it.get() }
        // The compiled resource APK is the base when there is one, which applyTo copies.
        if (result.resources.resourcesApk == null) input.copyTo(applied, overwrite = true)
        result.applyTo(applied)

        val written = tempDir.resolve("written.apk")
        patcher(input, "b", config, patches()).use { it.writeApk(written) }

        assertFalse(tempDir.resolve("b/patched/dex.zip").exists(), "the temporary DEX archive is deleted")
        assertFalse(tempDir.resolve("b/patched/other-files.zip").exists(), "the temporary archive is deleted")
        return applied to written
    }

    // endregion

    // region Assertions

    /**
     * Asserts that both APKs hold the same entries, with the same data compressed the same way, and are
     * aligned. applyTo adds an empty entry for every directory of the other files, which writeApk leaves out,
     * as the platform ignores them.
     */
    private fun assertSameApk(applied: File, written: File): Map<String, Entry> {
        val inputDirectories = entriesOf(tempDir.resolve("input/input.apk")).keys.filter { it.endsWith("/") }
        val appliedEntries = entriesOf(applied).filterKeys { name -> !name.endsWith("/") || name in inputDirectories }
        val writtenEntries = entriesOf(written)
        assertEquals(appliedEntries.keys.sorted(), writtenEntries.keys.sorted())
        appliedEntries.forEach { (name, entry) -> assertEquals(entry, writtenEntries[name], name) }
        assertAligned(applied)
        assertAligned(written)
        return writtenEntries
    }

    private fun assertCopiedAsTheyAre(input: File, written: File, vararg names: String) = names.forEach { name ->
        assertEquals(entryOf(input, name), entryOf(written, name), name)
    }

    private fun assertAligned(apk: File) = centralDirectory(apk).forEach { header ->
        if (header.method != ZipEntry.STORED) return@forEach
        val alignment = if (header.name.endsWith(".so")) 16384 else 4
        assertEquals(0L, header.dataOffset % alignment, "${header.name} in ${apk.name} is aligned to $alignment")
    }

    private fun assertSigns(apk: File) {
        val keyStore = ApkUtils.KeyStoreDetails(tempDir.resolve("test.keystore"), null, "alias", "password")
        ApkUtils.signApk(apk, apk, "Test", keyStore)
        assertTrue(ApkUtils.verifiedSigningCertificates(apk, PLATFORM_VERSION)!!.isNotEmpty(), "the signature verifies")
    }

    // endregion

    // region Archives

    private fun entriesOf(apk: File): Map<String, Entry> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().associate { entry ->
            val crc = CRC32()
            zip.getInputStream(entry).use { stream -> crc.update(stream.readBytes()) }
            entry.name to Entry(crc.value, entry.size, entry.method)
        }
    }

    private fun entryOf(apk: File, name: String) = entriesOf(apk).getValue(name)

    private fun read(apk: File, name: String) = ZipFile(apk).use { zip ->
        zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
    }

    private class InputBuilder(val zFile: ZFile) {
        fun add(name: String, contents: String) = add(name, contents.toByteArray())
        fun add(name: String, contents: ByteArray) = zFile.add(name, ByteArrayInputStream(contents))
        fun addStored(name: String, contents: ByteArray) = zFile.add(name, ByteArrayInputStream(contents), false)
    }

    /** [ResourceTestApk] with the entries [build] adds. */
    private fun inputApk(build: InputBuilder.() -> Unit): File {
        val apk = ResourceTestApk.build(tempDir.resolve("input").apply { mkdirs() })
        ZFile.openReadWrite(apk).use { zFile -> InputBuilder(zFile).build() }
        return apk
    }

    /**
     * Writes the entries of [apk] again with [ZipOutputStream], which writes deflated entries with a data
     * descriptor, as old build tools did. Entries that do not shrink are stored, as applyTo would store them.
     */
    private fun withDataDescriptors(apk: File): File {
        val output = tempDir.resolve("input/descriptors.apk")
        ZipFile(apk).use { zip ->
            ZipOutputStream(output.outputStream()).use { stream ->
                zip.entries().asSequence().forEach { entry ->
                    val contents = zip.getInputStream(entry).use { it.readBytes() }
                    val shrinks = ByteArrayOutputStream().also { bytes ->
                        DeflaterOutputStream(bytes, Deflater(1, true)).use { it.write(contents) }
                    }.size() < contents.size
                    stream.putNextEntry(
                        ZipEntry(entry.name).apply {
                            if (!shrinks || entry.method == ZipEntry.STORED) {
                                method = ZipEntry.STORED
                                size = contents.size.toLong()
                                compressedSize = contents.size.toLong()
                                crc = CRC32().apply { update(contents) }.value
                            }
                        },
                    )
                    stream.write(contents)
                    stream.closeEntry()
                }
            }
        }
        return output
    }

    private class CentralDirectoryHeader(val name: String, val method: Int, val dataOffset: Long)

    private fun centralDirectory(apk: File): List<CentralDirectoryHeader> {
        val bytes = apk.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(at: Int) = buffer.getShort(at).toInt() and 0xFFFF
        val eocd = (bytes.size - 22 downTo 0).first { buffer.getInt(it) == 0x06054b50 }
        var offset = buffer.getInt(eocd + 16)
        return List(u16(eocd + 10)) {
            val nameLength = u16(offset + 28)
            val name = String(bytes, offset + 46, nameLength, Charsets.UTF_8)
            val localHeader = buffer.getInt(offset + 42)
            val header = CentralDirectoryHeader(
                name,
                u16(offset + 10),
                localHeader + 30L + u16(localHeader + 26) + u16(localHeader + 28),
            )
            offset += 46 + nameLength + u16(offset + 30) + u16(offset + 32)
            header
        }
    }

    /** Entry name to (local header flags, central directory flags), read straight from the bytes. */
    private fun generalPurposeFlags(file: File): Map<String, Pair<Int, Int>> {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
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

    // endregion

    // region DEX files

    private fun dexOf(vararg types: String): ByteArray {
        val file = tempDir.resolve("dex/${types.joinToString("") { it.trim('L', ';') }}.dex").apply { parentFile.mkdirs() }
        DexPool(Opcodes.getDefault()).apply {
            types.forEach { type ->
                internClass(ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null))
            }
            writeTo(FileDataStore(file))
        }
        return file.readBytes()
    }

    private fun typesIn(dex: ByteArray) =
        DexBackedDexFile.fromInputStream(null, dex.inputStream().buffered()).classes.mapTo(HashSet()) { it.type }

    // endregion

    private companion object {
        const val PLATFORM_VERSION = 30
        val ON_REQUEST = PatcherBehavior.ON_REQUEST_RESOURCE_DECODING.since.toString()
    }
}
