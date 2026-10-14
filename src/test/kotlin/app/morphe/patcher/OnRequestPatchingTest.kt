/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.patch.PatchBundleInfo
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.coder.ResourceTestApk
import com.reandroid.apk.ApkModule
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Applies a resource patch with [Patcher], as built for the version of Morphe Patcher that decodes resources
 * on request and as built for an older one.
 */
internal class OnRequestPatchingTest {
    @TempDir
    private lateinit var tempDir: File

    /** Whether the layout, which the patch never reads, was decoded when the patch executed. */
    private var layoutDecoded: Boolean? = null

    private val patch = resourcePatch("Change a string") {
        execute {
            document("res/values/strings.xml").use { document ->
                val strings = document.getElementsByTagName("string")
                for (index in 0 until strings.length) {
                    val string = strings.item(index)
                    if (string.attributes.getNamedItem("name").nodeValue == "hello") string.textContent = "Patched"
                }
            }
            layoutDecoded = get("res/layout/main.xml", false).exists()
        }
    }

    private fun ResourcePatch.builtFor(patcherVersion: String, bundleName: String = "Patches") = apply {
        bundle = PatchBundleInfo(File("$bundleName.mpp"), bundleName, "1.0.0", patcherVersion)
    }

    private fun patch(vararg patches: ResourcePatch): PatcherResult {
        val apk = ResourceTestApk.build(tempDir)

        return Patcher(PatcherConfig(apk, tempDir.resolve("patcher"))).use { patcher ->
            patcher += patches.toSet()
            runBlocking { patcher().toList() }.forEach { assertNull(it.exception, it.exception?.stackTraceToString()) }
            patcher.get()
        }
    }

    private fun assertPatched(result: PatcherResult) {
        val resourcesApk = assertNotNull(result.resources.resourcesApk)
        ApkModule.loadApkFile(resourcesApk).use { module ->
            val pkg = module.tableBlock.pickOne()
            assertEquals("Patched", pkg.getEntry("", "string", "hello")!!.resValue.valueAsString)
            assertEquals("Hallo", pkg.getEntry("-de", "string", "hello")!!.resValue.valueAsString)
            assertEquals("Keep", pkg.getEntry("", "string", "keep")!!.resValue.valueAsString)
        }
    }

    @Test
    fun `patches built for decoding on request get only the resources they read`() {
        val result = patch(patch.builtFor(PatcherBehavior.ON_REQUEST_RESOURCE_DECODING.since.toString()))

        assertFalse(layoutDecoded!!)
        assertPatched(result)
    }

    @Test
    fun `patches built for an older version get every resource decoded up front`() {
        val result = patch(patch.builtFor("1.15.1"))

        assertTrue(layoutDecoded!!)
        assertPatched(result)
    }

    @Test
    fun `an older bundle gets every resource decoded up front for every bundle`() {
        val result = patch(
            patch.builtFor(PatcherBehavior.ON_REQUEST_RESOURCE_DECODING.since.toString(), "New patches"),
            resourcePatch("Old") { execute { } }.builtFor("1.15.1", "Old patches"),
        )

        assertTrue(layoutDecoded!!)
        assertPatched(result)
    }
}
