/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

@file:Suppress("unused")

package app.morphe.patcher.patch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.PatcherBehavior
import app.morphe.patcher.literal
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The compiled classes of these fingerprints are copied into test bundles.

private object UnchangedFingerprint : Fingerprint(
    filters = listOf(
        literal(1, location = InstructionLocation.MatchFirst()),
        opcode(Opcode.NOP),
        string("value", location = InstructionLocation.MatchAfterAnywhere()),
    )
)

private object MatchAfterImmediatelyFingerprint : Fingerprint(
    filters = listOf(
        opcode(Opcode.INVOKE_STATIC),
        opcode(Opcode.MOVE_RESULT_OBJECT, InstructionLocation.MatchAfterImmediately()),
    )
)

private object MatchBeforeWithinFingerprint : Fingerprint(
    filters = listOf(
        opcode(Opcode.RETURN_OBJECT),
        opcode(Opcode.INVOKE_STATIC, InstructionLocation.MatchBeforeWithin(3)),
    )
)

private object OpcodePatternFingerprint : Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(Opcode.NOP, Opcode.RETURN_VOID)
)

private object CustomLocationFingerprint : Fingerprint(
    filters = listOf(
        opcode(Opcode.NOP),
        opcode(Opcode.NOP, InstructionLocation { previous, current -> current == previous + 2 }),
    )
)

internal class PatchBundleInfoTest {
    @TempDir
    lateinit var tempDir: File

    private val oldVersion = "1.15.1"

    /**
     * A bundle with the compiled classes of [classes], and the [entries] by name.
     */
    private fun bundle(
        patcherVersion: String?,
        vararg classes: KClass<*>,
        entries: Map<String, String> = emptyMap(),
    ): File {
        val file = File.createTempFile("patches", ".mpp", tempDir)
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            patcherVersion?.let { mainAttributes.putValue(PatchBundleInfo.PATCHER_VERSION_ATTRIBUTE, it) }
        }

        JarOutputStream(file.outputStream(), manifest).use { jar ->
            fun add(name: String, bytes: ByteArray) {
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }

            classes.forEach { kClass ->
                val packagePath = kClass.java.packageName.replace('.', '/')
                val classesDirectory = File(kClass.java.protectionDomain.codeSource.location.toURI())
                    .resolve(packagePath)
                // Includes classes compiled from lambdas of the declaration.
                classesDirectory.listFiles { classFile ->
                    classFile.name == "${kClass.java.simpleName}.class" ||
                        classFile.name.startsWith("${kClass.java.simpleName}$")
                }!!.forEach { classFile -> add("$packagePath/${classFile.name}", classFile.readBytes()) }
            }
            entries.forEach { (name, content) -> add(name, content.toByteArray()) }
        }

        return file
    }

    private fun File.canChange() = PatchBundleInfo.read(this).retryingFiltersCanChangeMatches

    @Test
    fun `fingerprints using only locations that match the same instructions cannot change`() {
        assertFalse(bundle(oldVersion, UnchangedFingerprint::class).canChange())
        assertFalse(bundle(oldVersion).canChange(), "a bundle without bytecode")
    }

    @Test
    fun `fingerprints using locations that can match differently can change`() {
        listOf(
            MatchAfterImmediatelyFingerprint::class,
            MatchBeforeWithinFingerprint::class,
            OpcodePatternFingerprint::class,
            CustomLocationFingerprint::class,
        ).forEach { kClass ->
            assertTrue(bundle(oldVersion, UnchangedFingerprint::class, kClass).canChange(), "$kClass")
        }
    }

    @Test
    fun `only class and DEX files are checked`() {
        val location = "Lapp/morphe/patcher/InstructionLocation\$MatchAfterWithin;"

        assertTrue(bundle(oldVersion, entries = mapOf("classes.dex" to location)).canChange())
        assertFalse(bundle(oldVersion, entries = mapOf("locations.txt" to location)).canChange())
    }

    @Test
    fun `bundles not built for an older version are not checked`() {
        val newVersion = PatcherBehavior.RETRY_ALL_INSTRUCTION_FILTERS.since.toString()

        assertTrue(bundle(newVersion, UnchangedFingerprint::class).canChange())
        assertTrue(bundle(null, UnchangedFingerprint::class).canChange(), "a bundle built for an unknown version")
        assertTrue(PatchBundleInfo.read(tempDir.resolve("missing.mpp")).retryingFiltersCanChangeMatches)
    }
}
