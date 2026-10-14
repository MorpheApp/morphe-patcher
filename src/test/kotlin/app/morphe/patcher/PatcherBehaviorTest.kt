/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.PatcherBehavior.Adoption
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

    private fun resolve(
        patches: List<Patch<*>>,
        configured: String? = null,
        adoption: Adoption = onRequest.adoption,
    ) = resolvePatcherBehaviors(configured, patches, logger, listOf(onRequest)) { adoption }

    private val oldAndNew
        get() = listOf(
            resourcePatch("Old resources") {}.from(bundle("Old patches", "1.15.1")),
            bytecodePatch("Old bytecode") {}.from(bundle("Old bytecode patches", "1.15.1")),
            resourcePatch("Unknown") {}.from(bundle("Unknown patches", null)),
            resourcePatch("New") {}.from(bundle("New patches", newVersion)),
        )

    @Test
    fun `on request decoding needs every bundle to be built for it`() {
        assertEquals(Adoption.EVERY_BUNDLE, onRequest.adoption)
    }

    @Test
    fun `a behavior of every bundle applies if every bundle is built for it`() {
        val patches = listOf(
            resourcePatch("A") {}.from(bundle("A patches", newVersion)),
            resourcePatch("B") {}.from(bundle("B patches", "2.1.0")),
        )

        assertEquals(setOf(onRequest), resolve(patches, adoption = Adoption.EVERY_BUNDLE))
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `a behavior of every bundle does not apply if a bundle is built for an older version`() {
        assertEquals(emptySet(), resolve(oldAndNew, adoption = Adoption.EVERY_BUNDLE))

        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `a behavior of every bundle ignores bundles whose patches it does not affect`() {
        val patches = listOf(
            bytecodePatch("Old") {}.from(bundle("Old patches", "1.15.1")),
            resourcePatch("New") {}.from(bundle("New patches", newVersion)),
        )

        assertEquals(setOf(onRequest), resolve(patches, adoption = Adoption.EVERY_BUNDLE))
    }

    @Test
    fun `a behavior of the newest bundle applies to the patches of every bundle`() {
        assertEquals(setOf(onRequest), resolve(oldAndNew, adoption = Adoption.NEWEST_BUNDLE))

        val warning = warnings.single()
        assertTrue("Old patches 1.0.0, built for Morphe Patcher 1.15.1" in warning, warning)
        assertTrue("Unknown patches 1.0.0, built for Morphe Patcher of an unknown version" in warning, warning)
        assertTrue("New patches 1.0.0" in warning, "the newest bundle is named: $warning")
        assertTrue(onRequest.description in warning, warning)
        assertFalse("Old bytecode patches" in warning, "resource decoding does not affect bytecode patches")
    }

    @Test
    fun `patches that were not loaded from a bundle use the behavior of the oldest version`() {
        Adoption.entries.forEach { adoption ->
            assertEquals(emptySet(), resolve(listOf(resourcePatch("Patch") {}), adoption = adoption))
            assertEquals(
                emptySet(),
                resolve(listOf(resourcePatch("Patch") {}.from(bundle("Patches", null))), adoption = adoption),
                "a bundle without a patcher version counts as the oldest",
            )
        }
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a configured version takes precedence`() {
        val patches = listOf(resourcePatch("New") {}.from(bundle("New patches", newVersion)))

        assertEquals(emptySet(), resolve(patches, configured = "1.15.1"))
        assertEquals(emptySet(), resolve(patches, configured = "not a version"))
        assertTrue(warnings.single().contains("not a version"))
        warnings.clear()

        assertEquals(setOf(onRequest), resolve(oldAndNew, configured = newVersion, adoption = Adoption.EVERY_BUNDLE))
        assertTrue("Old patches 1.0.0" in warnings.single(), "outdated bundles are warned about")
    }

    @Test
    fun `exact fingerprint strings apply to every bundle once one is built for them`() {
        val exactStrings = PatcherBehavior.EXACT_FINGERPRINT_STRINGS
        assertEquals(Adoption.NEWEST_BUNDLE, exactStrings.adoption)

        val newVersion = exactStrings.since.toString()
        val patches = listOf(
            bytecodePatch("Old") {}.from(bundle("Old patches", "1.15.1")),
            resourcePatch("Old resources") {}.from(bundle("Old resource patches", "1.15.1")),
            bytecodePatch("New") {}.from(bundle("New patches", newVersion)),
        )
        val resolved = resolvePatcherBehaviors(null, patches, logger, listOf(exactStrings))

        assertEquals(setOf(exactStrings), resolved)
        val warning = warnings.single()
        assertTrue("Old patches 1.0.0, built for Morphe Patcher 1.15.1" in warning, warning)
        assertFalse("Old resource patches" in warning, "fingerprints do not affect resource patches")

        warnings.clear()
        assertEquals(emptySet(), resolvePatcherBehaviors(null, patches.take(2), logger, listOf(exactStrings)))
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `exact fingerprint strings apply to patches built for an unknown version`() {
        val exactStrings = PatcherBehavior.EXACT_FINGERPRINT_STRINGS
        fun resolve(vararg patches: Patch<*>) =
            resolvePatcherBehaviors(null, patches.toList(), logger, listOf(exactStrings))

        assertEquals(setOf(exactStrings), resolve(bytecodePatch("Patch") {}), "not loaded from a bundle")
        assertEquals(setOf(exactStrings), resolve(bytecodePatch("Patch") {}.from(bundle("Patches", null))))
        assertTrue(warnings.isEmpty(), warnings.toString())

        val old = bytecodePatch("Old") {}.from(bundle("Old patches", "1.15.1"))
        assertEquals(emptySet(), resolve(old))
        assertEquals(emptySet(), resolve(old, resourcePatch("Unknown") {}), "only affected patches count")

        assertEquals(setOf(exactStrings), resolve(old, bytecodePatch("Unknown") {}.from(bundle("Unknown patches", null))))
        val warning = warnings.single()
        assertTrue("Old patches 1.0.0, built for Morphe Patcher 1.15.1" in warning, warning)
        assertTrue("behavior of Morphe Patcher ${exactStrings.since}" in warning, warning)
        assertFalse("Unknown patches" in warning, warning)
    }

    @Test
    fun `bundles of the same behavior are not warned about`() {
        val patches = listOf(
            resourcePatch("A") {}.from(bundle("A patches", "1.15.0")),
            resourcePatch("B") {}.from(bundle("B patches", "1.15.1")),
        )

        Adoption.entries.forEach { resolve(patches, adoption = it) }

        assertTrue(warnings.isEmpty(), warnings.toString())
    }
}
