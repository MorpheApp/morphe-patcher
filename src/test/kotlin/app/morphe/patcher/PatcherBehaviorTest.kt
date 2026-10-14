/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchBundleInfo
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.junit.jupiter.api.Test
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PatcherBehaviorTest {
    private val warnings = mutableListOf<String>()
    private val logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level == Level.WARNING) warnings += record.message
            }

            override fun flush() {}
            override fun close() {}
        })
    }

    private val onRequest = PatcherBehavior.ON_REQUEST_RESOURCE_DECODING
    private val newVersion = onRequest.since.toString()

    private fun bundle(name: String, patcherVersion: String?) =
        PatchBundleInfo(File("$name.mpp"), name, "1.0.0", patcherVersion)

    private fun <P : Patch<*>> P.from(bundle: PatchBundleInfo) = apply { this.bundle = bundle }

    @Test
    fun `patches use the behavior of the newest bundle they were loaded from`() {
        val patches = listOf(
            resourcePatch("Old") {}.from(bundle("Old patches", "1.15.1")),
            bytecodePatch("New") {}.from(bundle("New patches", newVersion)),
        )

        val target = targetPatcherVersion(null, patches, logger)

        assertEquals(newVersion, target.toString())
        assertTrue(onRequest.appliesTo(target))
    }

    @Test
    fun `patches that were not loaded from a bundle use the behavior of the oldest version`() {
        val target = targetPatcherVersion(null, listOf(resourcePatch("Patch") {}), logger)

        assertNull(target)
        assertFalse(onRequest.appliesTo(target))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a bundle without a patcher version counts as the oldest`() {
        val target = targetPatcherVersion(null, listOf(resourcePatch("Patch") {}.from(bundle("Patches", null))), logger)

        assertNull(target)
    }

    @Test
    fun `a configured version takes precedence`() {
        val patches = listOf(resourcePatch("New") {}.from(bundle("New patches", newVersion)))

        assertEquals("1.15.1", targetPatcherVersion("1.15.1", patches, logger).toString())
        assertNull(targetPatcherVersion("not a version", patches, logger))
        assertTrue(warnings.single().contains("not a version"))
    }

    @Test
    fun `older bundles whose patches a newer behavior affects are named in a warning`() {
        val patches = listOf(
            resourcePatch("Old resources") {}.from(bundle("Old patches", "1.15.1")),
            bytecodePatch("Old bytecode") {}.from(bundle("Old bytecode patches", "1.15.1")),
            resourcePatch("Unknown") {}.from(bundle("Unknown patches", null)),
            resourcePatch("New") {}.from(bundle("New patches", newVersion)),
        )

        targetPatcherVersion(null, patches, logger)

        val warning = warnings.single()
        assertTrue("Old patches 1.0.0, built for Morphe Patcher 1.15.1" in warning, warning)
        assertTrue("Unknown patches 1.0.0, built for Morphe Patcher of an unknown version" in warning, warning)
        assertTrue("New patches 1.0.0" in warning, "the newest bundle is named: $warning")
        assertTrue(onRequest.description in warning, warning)
        assertFalse("Old bytecode patches" in warning, "resource decoding does not affect bytecode patches")
    }

    @Test
    fun `bundles of the same behavior are not warned about`() {
        val patches = listOf(
            resourcePatch("A") {}.from(bundle("A patches", "1.15.0")),
            resourcePatch("B") {}.from(bundle("B patches", "1.15.1")),
        )

        targetPatcherVersion(null, patches, logger)

        assertTrue(warnings.isEmpty(), warnings.toString())
    }
}
