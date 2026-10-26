@file:Suppress("unused")

package app.morphe.patcher.patch

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.reflect.KFunction
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// region Test patches.

// Not loaded, because it's unnamed.
val publicUnnamedPatch = bytecodePatch(default = true) {
}

// Not loaded, because it's private, but remembers the bundle of the patch that depends on it.
private val privateUnnamedDependency = bytecodePatch {
}

// Loaded, because it's named.
val publicPatch = bytecodePatch("Public") {
    dependsOn(privateUnnamedDependency)
}

// Not loaded, because it's private.
private val privateUnnamedPatch = bytecodePatch(default = true) {
}

// Not loaded, because it's private.
private val privatePatch = bytecodePatch("Private") {
}

// Not loaded, because it's unnamed.
fun publicUnnamedPatchFunction() = publicUnnamedPatch

// Loaded, because it's named.
fun publicNamedPatchFunction() = bytecodePatch("Public") { }

// Not loaded, because it's parameterized.
fun parameterizedFunction(@Suppress("UNUSED_PARAMETER") param: Any) = publicNamedPatchFunction()

// Not loaded, because it's private.
private fun privateUnnamedPatchFunction() = privateUnnamedPatch

// Not loaded, because it's private.
private fun privateNamedPatchFunction() = privatePatch

// endregion

internal object PatchLoaderTest {
    private const val LOAD_PATCHES_FUNCTION_NAME = "loadPatches"
    private val TEST_PATCHES_CLASS = ::publicPatch.javaField!!.declaringClass.name
    private val TEST_PATCHES_CLASS_LOADER = ::publicPatch.javaClass.classLoader

    /**
     * Calls the private PatchLoader.Companion.loadPatches function with the test patches.
     */
    private fun loadPatches(patchesFile: File): Set<Patch<*>> {
        // Get instance of private PatchLoader.Companion class.
        val patchLoaderCompanionObject = getPrivateFieldByType(
            PatchLoader::class.java,
            PatchLoader::class.companionObject!!.javaObjectType,
        )

        // Get private PatchLoader.Companion.loadPatches function from PatchLoader.Companion.
        @Suppress("UNCHECKED_CAST")
        val loadPatchesFunction = getPrivateFunctionByName(
            patchLoaderCompanionObject,
            LOAD_PATCHES_FUNCTION_NAME,
        ) as KFunction<Map<File, Set<Patch<*>>>>

        // Call private PatchLoader.Companion.loadPatches function.
        return loadPatchesFunction.call(
            patchLoaderCompanionObject,
            TEST_PATCHES_CLASS_LOADER,
            mapOf(patchesFile to setOf(TEST_PATCHES_CLASS)),
        ).values.first()
    }

    @Test
    fun `loads patches correctly`() {
        val patches = loadPatches(File("patchesFile"))

        assertEquals(
            2,
            patches.size,
            "Expected 2 patches to be loaded, " +
                "because there's only two named patches declared as public static fields " +
                "or returned by public static and non-parametrized methods.",
        )
    }

    @Test
    fun `loaded patches remember the bundle they were loaded from`(@TempDir tempDir: File) {
        val patchesFile = tempDir.resolve("patches.mpp")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Name", "Test patches")
            mainAttributes.putValue("Version", "2.3.4")
            mainAttributes.putValue("Patcher-Version", "1.16.0")
        }
        JarOutputStream(patchesFile.outputStream(), manifest).close()

        val patches = loadPatches(patchesFile)

        patches.forEach { patch ->
            assertEquals(
                PatchBundleInfo(patchesFile, "Test patches", "2.3.4", "1.16.0", retryingFiltersCanChangeMatches = false),
                patch.bundle,
            )
        }
        assertEquals(
            publicPatch.bundle,
            publicPatch.dependencies.single().bundle,
            "a dependency that is not loaded itself is built with the patch that depends on it",
        )

        // A bundle that has no manifest declares nothing.
        loadPatches(tempDir.resolve("missing.mpp")).forEach { patch ->
            assertNull(patch.bundle!!.patcherVersion)
            assertEquals("missing.mpp", patch.bundle.toString())
        }
        assertEquals(publicPatch.bundle, publicPatch.dependencies.single().bundle)
    }

    @Test
    fun `universal patches declared with default true fail to load from bundles built for 2_0_0`(@TempDir tempDir: File) {
        assertTrue(publicPatch.declaredUniversalWithDefault)
        assertFalse(publicPatch.default)
        assertEquals("PatchLoaderTest.kt", publicPatch.declaredAt?.fileName, "the patch is declared in this file")

        fun bundle(patcherVersion: String) = tempDir.resolve("patches-$patcherVersion.mpp").also {
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes.putValue("Name", "Test patches")
                mainAttributes.putValue("Patcher-Version", patcherVersion)
            }
            JarOutputStream(it.outputStream(), manifest).close()
        }

        val warnings = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level == Level.WARNING) warnings += record.message
            }

            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger(PatchLoader::class.java.name)
        logger.addHandler(handler)
        try {
            loadPatches(bundle("1.16.0")).forEach { assertFalse(it.default) }
        } finally {
            logger.removeHandler(handler)
        }

        val warning = warnings.single()
        assertTrue("so they are loaded with default false" in warning, warning)
        assertTrue("Test patches (${bundle("1.16.0")})" in warning, "the bundle is named: $warning")
        assertTrue("\"Public\" declared at" in warning && "PatchLoaderTest.kt" in warning, warning)

        val exception = assertFails { loadPatches(bundle("2.0.0-dev.5")) }
        val message = generateSequence(exception) { it.cause }.filterIsInstance<PatchException>().first().message!!
        assertTrue("Test patches (${bundle("2.0.0-dev.5")})" in message, message)
        assertTrue("\"Public\" declared at" in message && "PatchLoaderTest.kt" in message, message)
    }

    /**
     * A bundle with entries by name, holding the header of a class file of the version.
     */
    private fun bundleWithClassFileVersions(file: File, versions: Map<String, Int>) = file.also {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Name", "Test patches")
        }
        JarOutputStream(it.outputStream(), manifest).use { jar ->
            versions.forEach { (name, version) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(ByteBuffer.allocate(8).putInt(0xCAFEBABE.toInt()).putShort(0).putShort(version.toShort()).array())
                jar.closeEntry()
            }
        }
    }

    @Test
    fun `bundles with classes compiled for Java 17 or older load`(@TempDir tempDir: File) {
        val patchesFile = bundleWithClassFileVersions(
            tempDir.resolve("patches.mpp"),
            mapOf(
                "app/Java11.class" to 55,
                "app/Java17.class" to 61,
                // Never loaded.
                "META-INF/versions/21/app/Java21.class" to 65,
                "app/Java21.txt" to 65,
            ),
        )

        assertEquals(2, loadPatches(patchesFile).size)
    }

    @Test
    fun `bundles with classes compiled for a newer Java version than 17 fail to load`(@TempDir tempDir: File) {
        val patchesFile = tempDir.resolve("patches.mpp")
        val versions = mapOf("app/Java17.class" to 61) + (1..12).associate { "app/Java21_$it.class" to 65 }
        bundleWithClassFileVersions(patchesFile, versions)

        val exception = assertFails { loadPatches(patchesFile) }
        val message = generateSequence(exception) { it.cause }.filterIsInstance<PatchException>().first().message!!

        assertTrue("Test patches ($patchesFile)" in message, "the bundle is named: $message")
        assertTrue("Java 17 or older" in message, message)
        assertTrue("app/Java21_1.class (Java 21, class file version 65)" in message, message)
        assertTrue("app/Java21_10.class" in message && "app/Java21_11.class" !in message, message)
        assertTrue("... and 2 more" in message, message)
        assertFalse("app/Java17.class" in message, message)
    }

    private fun getPrivateFieldByType(cls: Class<*>, fieldType: Class<*>) =
        cls.declaredFields.first { it.type == fieldType }.apply { isAccessible = true }.get(null)

    private fun getPrivateFunctionByName(obj: Any, @Suppress("SameParameterValue") methodName: String) =
        obj::class.declaredFunctions.first { it.name == methodName }.apply { isAccessible = true }
}
