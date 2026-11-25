/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

@file:Suppress("DEPRECATION")

package app.morphe.patcher.profile

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.coder.ResourceTestApk
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class BaselineProfileTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a profile reads back as it is written`() {
        val profile = BaselineProfile(
            listOf(
                dexProfile("classes.dex", 0x12345678, methodCount = 40, classes = intArrayOf(1, 5, 9), methods = mapOf(0 to HOT, 3 to (STARTUP or HOT), 39 to POST_STARTUP)),
                dexProfile("classes2.dex", -7, methodCount = 3, classes = intArrayOf(), methods = mapOf(2 to (STARTUP or POST_STARTUP))),
            ),
        )

        val read = BaselineProfile.read(profile.writeAsset())

        assertEquals(profile.dexFiles.map { it.describe() }, read.dexFiles.map { it.describe() })
    }

    @Test
    fun `a profile keyed as ART keys it names the DEX entries`() {
        val profile = BaselineProfile(
            listOf(
                dexProfile("classes.dex", 1, methodCount = 2, classes = intArrayOf(0), methods = mapOf(1 to HOT)),
                dexProfile("classes3.dex", 2, methodCount = 2, classes = intArrayOf(1), methods = mapOf(0 to STARTUP)),
            ),
        )

        val read = BaselineProfile.read(profile.writeArtProfile(ArtProfileVersion.V010))

        assertEquals(listOf("classes.dex", "classes3.dex"), read.dexFiles.map { it.name })
        assertEquals(profile.dexFiles.map { it.describe() }, read.dexFiles.map { it.describe() })
    }

    @Test
    fun `STRIP_FAST carries the profile over to the stripped and the new DEX files`() {
        val input = inputApk()
        val patches = { setOf<Patch>(bytecodePatch("Change a class") { execute { mutableClassDefBy("La;") } }) }

        val written = tempDir.resolve("written.apk")
        patcher(input, "b", patches()).use { it.writeApk(written) }
        val applied = tempDir.resolve("applied.apk")
        val result = patcher(input, "a", patches()).use { it.get() }
        if (result.resources.resourcesApk == null) input.copyTo(applied, overwrite = true)
        result.applyTo(applied)

        for (apk in listOf(written, applied)) {
            val profile = described(apk)
            assertEquals(
                mapOf(
                    // The changed class moved to the new DEX file with its methods.
                    "classes.dex" to Described(setOf("Lb;"), mapOf("Lb;->c()V" to POST_STARTUP)),
                    "classes2.dex" to Described(setOf("Lc;"), mapOf("Lc;->d()V" to HOT)),
                    "classes3.dex" to Described(setOf("La;"), mapOf("La;->a()V" to HOT, "La;->b()V" to STARTUP)),
                ),
                profile,
                apk.name,
            )
            assertMetadataMatches(apk)
            assertEmbeddedProfileMatches(apk)
        }
        ZipFile(written).use { zip ->
            BaselineProfile.ENTRIES.forEach { name -> assertEquals(ZipEntry.STORED, zip.getEntry(name).method, name) }
        }
    }

    @Test
    fun `a profile that no longer matches the input is left out`() {
        val input = inputApk(checksumOffset = 1)
        val patches = { setOf<Patch>(bytecodePatch("Change a class") { execute { mutableClassDefBy("La;") } }) }

        val written = tempDir.resolve("written.apk")
        patcher(input, "b", patches()).use { it.writeApk(written) }

        ZipFile(written).use { zip ->
            BaselineProfile.ENTRIES.forEach { name -> assertNull(zip.getEntry(name), name) }
        }
    }

    @Test
    fun `dex metadata holds the profile for the version of ART`() {
        val input = inputApk()
        val written = tempDir.resolve("written.apk")
        patcher(input, "b", setOf(bytecodePatch("Change a class") { execute { mutableClassDefBy("La;") } }))
            .use { it.writeApk(written) }

        assertNull(DexMetadata.of(written, 27))
        for ((sdk, version) in listOf(28 to "010", 30 to "010", 31 to "015", 36 to "015")) {
            val metadata = assertNotNull(DexMetadata.of(written, sdk))
            val primary = ZipInputStream(ByteArrayInputStream(metadata)).use { zip ->
                val entry = assertNotNull(zip.nextEntry)
                assertEquals("primary.prof", entry.name)
                assertEquals(ZipEntry.STORED, entry.method)
                zip.readBytes()
            }
            assertEquals("pro\u0000$version\u0000", String(primary, 0, 8, Charsets.ISO_8859_1), "API $sdk")
        }
        val primary = ZipInputStream(ByteArrayInputStream(DexMetadata.of(written, 29)!!)).use { it.nextEntry; it.readBytes() }
        assertEquals(
            setOf("base.apk", "base.apk!classes2.dex", "base.apk!classes3.dex").map { it.substringAfter('!').let { name -> if (name == "base.apk") "classes.dex" else name } }.toSet(),
            BaselineProfile.read(primary).dexFiles.map { it.name }.toSet(),
        )
    }

    @Test
    fun `dex metadata is not written for a profile that does not match the APK`() {
        assertNull(DexMetadata.of(inputApk(checksumOffset = 1), 34))
        assertNotNull(DexMetadata.of(inputApk(), 34))
    }

    // region Input

    private fun inputApk(checksumOffset: Int = 0): File {
        val classes = dexOf(classDef("La;", "a", "b"), classDef("Lb;", "c"))
        val classes2 = dexOf(classDef("Lc;", "d"))
        val apk = ResourceTestApk.build(tempDir.resolve("input${checksumOffset}").apply { mkdirs() }, false)
        val profile = BaselineProfile(
            listOf(
                profileOf("classes.dex", classes, checksumOffset, setOf("La;", "Lb;"), mapOf("La;->a()V" to HOT, "La;->b()V" to STARTUP, "Lb;->c()V" to POST_STARTUP)),
                profileOf("classes2.dex", classes2, checksumOffset, setOf("Lc;"), mapOf("Lc;->d()V" to HOT)),
            ),
        )
        ZFile.openReadWrite(apk).use { zFile ->
            zFile.add("classes.dex", ByteArrayInputStream(classes))
            zFile.add("classes2.dex", ByteArrayInputStream(classes2))
            zFile.add(BaselineProfile.PROFILE_ENTRY, ByteArrayInputStream(profile.writeAsset()), false)
            zFile.add(BaselineProfile.METADATA_ENTRY, ByteArrayInputStream(profile.writeMetadata()), false)
        }
        return apk
    }

    private fun profileOf(name: String, dex: ByteArray, checksumOffset: Int, classes: Set<String>, methods: Map<String, Int>): DexProfile {
        val ids = DexIds(ByteBuffer.wrap(dex))
        val types = (0 until ids.typeCount).associateBy(ids::typeDescriptor)
        val methodIndices = (0 until ids.methodCount).associateBy(ids::methodSignature)
        val definitionTypes = ids.classDefTypes()
        val classTypes = classes.map { types.getValue(it) }.sorted().toIntArray()
        val sortedMethods = methods.entries.map { methodIndices.getValue(it.key) to it.value }.sortedBy { it.first }
        return DexProfile(
            name = name,
            checksum = crcOf(dex) + checksumOffset,
            typeCount = ids.typeCount,
            methodCount = ids.methodCount,
            classes = classTypes,
            classDefs = classTypes.map { definitionTypes.indexOf(it) }.sorted().toIntArray(),
            methods = sortedMethods.map { it.first }.toIntArray(),
            methodFlags = sortedMethods.map { it.second.toByte() }.toByteArray(),
        )
    }

    private fun classDef(type: String, vararg methods: String) = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        methods.map { name ->
            ImmutableMethod(
                type, name, null, "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
            )
        },
    )

    private fun dexOf(vararg classes: ImmutableClassDef): ByteArray {
        val file = tempDir.resolve("dex/${classes.joinToString("") { it.type.trim('L', ';') }}.dex").apply { parentFile.mkdirs() }
        DexPool(Opcodes.getDefault()).apply {
            classes.forEach(::internClass)
            writeTo(FileDataStore(file))
        }
        return file.readBytes()
    }

    private fun patcher(input: File, name: String, patches: Set<Patch>): Patcher =
        Patcher(PatcherConfig(input, tempDir.resolve(name), useBytecodeMode = BytecodeMode.STRIP_FAST)).also { patcher ->
            patcher += patches
            runBlocking { patcher().toList() }.forEach { assertNull(it.exception, it.exception?.stackTraceToString()) }
        }

    // endregion

    // region Assertions

    private data class Described(val classes: Set<String>, val methods: Map<String, Int>)

    /** The profile of [apk] by descriptors, checking that every part matches its DEX entry. */
    private fun described(apk: File): Map<String, Described> = ZipFile(apk).use { zip ->
        val profile = BaselineProfile.read(zip.getInputStream(zip.getEntry(BaselineProfile.PROFILE_ENTRY)).readBytes())
        profile.dexFiles.associate { dex ->
            val entry = assertNotNull(zip.getEntry(dex.name), dex.name)
            assertEquals(entry.crc.toInt(), dex.checksum, dex.name)
            val ids = DexIds(ByteBuffer.wrap(zip.getInputStream(entry).readBytes()))
            assertEquals(ids.methodCount, dex.methodCount, dex.name)
            dex.name to Described(
                dex.classes.mapTo(HashSet()) { ids.typeDescriptor(it) },
                dex.methods.indices.associate { ids.methodSignature(dex.methods[it]) to dex.methodFlags[it].toInt() },
            )
        }
    }

    private fun assertMetadataMatches(apk: File) = ZipFile(apk).use { zip ->
        val profile = BaselineProfile.read(zip.getInputStream(zip.getEntry(BaselineProfile.PROFILE_ENTRY)).readBytes())
        val metadata = zip.getInputStream(zip.getEntry(BaselineProfile.METADATA_ENTRY)).readBytes()
        val header = ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("prm\u0000002\u0000", String(metadata, 0, 8, Charsets.ISO_8859_1))
        assertEquals(profile.dexFiles.size, header.getShort(8).toInt())
        val body = ByteBuffer.wrap(ProfileCompression.inflate(metadata, 18, header.getInt(14), header.getInt(10)))
            .order(ByteOrder.LITTLE_ENDIAN)
        profile.dexFiles.forEachIndexed { index, dex ->
            assertEquals(index, body.short.toInt())
            val key = ByteArray(body.short.toInt()).also { body.get(it) }.toString(Charsets.UTF_8)
            assertEquals(dex.name, key)
            val ids = DexIds(ByteBuffer.wrap(zip.getInputStream(zip.getEntry(dex.name)).readBytes()))
            assertEquals(ids.typeCount, body.int)
            var definition = 0
            val definitions = IntArray(body.short.toInt()) { definition += body.short.toInt(); definition }
            val definitionTypes = ids.classDefTypes()
            assertEquals(dex.classes.toSet(), definitions.map { definitionTypes[it] }.toSet(), dex.name)
        }
        assertTrue(!body.hasRemaining())
    }

    private fun assertEmbeddedProfileMatches(apk: File) = ZipFile(apk).use { zip ->
        val embedded = zip.getInputStream(zip.getEntry(BaselineProfile.EMBEDDED_PROFILE_ENTRY)).readBytes()
        val buffer = ByteBuffer.wrap(embedded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("pro\u0000015\u0000", String(embedded, 0, 8, Charsets.ISO_8859_1))
        // The DEX files section comes first and is stored.
        assertEquals(0, buffer.getInt(12))
        val dexSection = ByteBuffer.wrap(embedded, buffer.getInt(16), buffer.getInt(20)).slice().order(ByteOrder.LITTLE_ENDIAN)
        val profile = BaselineProfile.read(zip.getInputStream(zip.getEntry(BaselineProfile.PROFILE_ENTRY)).readBytes())
        assertEquals(profile.dexFiles.size, dexSection.short.toInt())
        profile.dexFiles.forEach { dex ->
            val ids = DexIds(ByteBuffer.wrap(zip.getInputStream(zip.getEntry(dex.name)).readBytes()))
            assertEquals(dex.checksum, dexSection.int)
            assertEquals(ids.typeCount, dexSection.int)
            assertEquals(ids.methodCount, dexSection.int)
            val key = ByteArray(dexSection.short.toInt()).also { dexSection.get(it) }.toString(Charsets.UTF_8)
            assertEquals(if (dex.name == "classes.dex") "base.apk" else "base.apk!${dex.name}", key)
        }
    }

    // endregion

    private fun dexProfile(name: String, checksum: Int, methodCount: Int, classes: IntArray, methods: Map<Int, Int>): DexProfile {
        val sorted = methods.toSortedMap()
        return DexProfile(
            name, checksum, DexProfile.UNKNOWN, methodCount, classes, IntArray(0),
            sorted.keys.toIntArray(), sorted.values.map { it.toByte() }.toByteArray(),
        )
    }

    private fun DexProfile.describe() = listOf(name, checksum, methodCount, classes.toList(), methods.toList(), methodFlags.toList())

    private fun crcOf(bytes: ByteArray) = java.util.zip.CRC32().apply { update(bytes) }.value.toInt()
}
