package app.morphe.patcher.apk

import app.morphe.patcher.patch.ApkArchitecture
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ABIs an APK or split archive is read to carry. For an archive they stand in for the lib/
 * directories of the APK its modules are merged into, which is what patches declare their
 * architecture against.
 */
internal class AbiTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `an APK is read from its lib directory`() {
        val apk = zip("app.apk", "AndroidManifest.xml" to ByteArray(0), "lib/x86_64/a.so" to ByteArray(0), "lib/x86/a.so" to ByteArray(0))

        assertEquals(listOf("x86_64", "x86"), Abi.of(apk))
    }

    @Test
    fun `config splits are read by the ABI they are named after`() {
        val file = archive("base.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk")

        assertEquals(listOf("arm64-v8a"), Abi.of(file))
    }

    @Test
    fun `an ABI is not claimed by the shorter name it contains`() {
        val file = archive("base.apk", "split_config.armeabi_v7a.apk", "split_config.x86_64.apk")

        assertEquals(listOf("armeabi-v7a", "x86_64"), Abi.of(file))
    }

    @Test
    fun `an archive without ABI splits carries no architecture`() {
        assertEquals(emptyList(), Abi.of(archive("base.apk", "split_config.en.apk")))
    }

    @Test
    fun `nested APKs are not split modules`() {
        assertEquals(emptyList(), Abi.of(archive("base.apk", "res/raw/config.arm64-v8a.apk")))
    }

    @Test
    fun `a base module keeping the native libraries is read from its lib directory`() {
        val file = zip(
            "bundle.apks",
            "base.apk" to module("AndroidManifest.xml", "lib/arm64-v8a/libapp.so", "lib/armeabi-v7a/libapp.so", "classes.dex"),
            "split_config.xxhdpi.apk" to module("resources.arsc"),
        )

        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), Abi.of(file))
    }

    @Test
    fun `an ABI split names the architecture even when the base module has libraries too`() {
        val file = zip(
            "bundle.apks",
            "base.apk" to module("lib/armeabi-v7a/libapp.so"),
            "split_config.arm64_v8a.apk" to module("lib/arm64-v8a/libapp.so"),
        )

        assertEquals(listOf("arm64-v8a"), Abi.of(file))
    }

    // Which of these names is an architecture patches can be asked about is up to the caller, so a
    // directory nothing runs is reported rather than dropped
    @Test
    fun `lib directories are reported as they are named`() {
        assertEquals(listOf("mips"), Abi.of(zip("bundle.apks", "base.apk" to module("lib/mips/libapp.so", "classes.dex"))))
    }

    @Test
    fun `a base module without native libraries carries no architecture`() {
        assertEquals(emptyList(), Abi.of(zip("bundle.apks", "base.apk" to module("AndroidManifest.xml", "classes.dex"))))
    }

    @Test
    fun `a file that cannot be read carries no architecture`() {
        val garbage = temporaryDirectory.resolve("garbage.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertEquals(emptyList(), Abi.of(garbage))
    }

    @Test
    fun `names map to the architectures patches declare against`() {
        assertEquals(ApkArchitecture.ARMEABI_V7A, Abi.architectureOf("armeabi"))
        assertEquals(ApkArchitecture.X86_64, Abi.architectureOf("X86_64"))
        assertNull(Abi.architectureOf("riscv64"))
        assertEquals("x86_64", Abi.namedIn("split_config.x86_64.apk"))
        assertEquals("x86", Abi.ofLibraryPath("lib/x86/a.so"))
        assertNull(Abi.ofLibraryPath("assets/lib/x86/a.so"))
    }

    // A module holding nothing but the entry names, which is all the ABIs are read out of
    private fun module(vararg entries: String): ByteArray = bytes(*entries.map { it to it.toByteArray() }.toTypedArray())

    private fun archive(vararg modules: String): File =
        zip("bundle-${System.nanoTime()}.apks", *modules.map { it to it.toByteArray() }.toTypedArray())

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File =
        temporaryDirectory.resolve(name).apply { writeBytes(bytes(*entries)) }

    private fun bytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
