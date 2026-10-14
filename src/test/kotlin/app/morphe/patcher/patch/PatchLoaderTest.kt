@file:Suppress("unused")

package app.morphe.patcher.patch

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.reflect.KFunction
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
            assertEquals(PatchBundleInfo(patchesFile, "Test patches", "2.3.4", "1.16.0"), patch.bundle)
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

    private fun getPrivateFieldByType(cls: Class<*>, fieldType: Class<*>) =
        cls.declaredFields.first { it.type == fieldType }.apply { isAccessible = true }.get(null)

    private fun getPrivateFunctionByName(obj: Any, @Suppress("SameParameterValue") methodName: String) =
        obj::class.declaredFunctions.first { it.name == methodName }.apply { isAccessible = true }
}
