/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import app.morphe.patcher.resource.coder.ArsclibResourceCoder
import app.morphe.patcher.resource.coder.ResourceTestApk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ids read from the resource table must be the ids a [PublicXmlManager] reads from the
 * `public.xml` decoded from that table.
 */
internal class TablePublicIdsTest {
    @TempDir
    private lateinit var tempDir: File

    private val coders = mutableListOf<ArsclibResourceCoder>()

    @AfterEach
    fun tearDown() = coders.forEach(ArsclibResourceCoder::close)

    /** The ids of the test APK read both ways, from a coder that decodes on request. */
    private fun bothWays(block: (TablePublicIds, PublicXmlManager) -> Unit) {
        val coder = ArsclibResourceCoder(tempDir.resolve("working").apply { mkdirs() }, ResourceTestApk.build(tempDir))
            .also(coders::add)
        coder.decodeResources(onRequest = true)
        val decoder = coder.onRequestDecoder!!
        val table = TablePublicIds(decoder.packageBlockOf(decoder.packages.getValue("com.test.app")))
        PublicXmlManager(coder.getFile("res/values/public.xml", null, true)).use { manager -> block(table, manager) }
    }

    @Test
    fun `the table has the ids of public xml`() = bothWays { table, manager ->
        val fileIds = manager.getDefinedIds()
        assertTrue(fileIds.isNotEmpty())

        val types = fileIds.keys.mapTo(mutableSetOf()) { it.first }
        types.forEach { type ->
            val expected = fileIds.filterKeys { it.first == type }.mapKeys { it.key.second }
            assertEquals(expected, table.idsOf(type), "ids of $type")
            assertEquals(manager.getHighestResourceIds()[type], table.highestId(type), "highest id of $type")
            expected.keys.forEach { assertTrue(table.idExists(type, it)) }
        }
        assertFalse(table.idExists("string", "missing"))
        assertEquals(emptyMap(), table.idsOf("dimen"))
        assertEquals(0, table.highestId("dimen"))
        assertEquals(emptyMap(), table.idsToDeclare(), "nothing is created, so nothing is declared")
    }

    @Test
    fun `the table gives the ids public xml gives`() = bothWays { table, manager ->
        val before = manager.getDefinedIds().keys
        val calls = listOf(
            "string" to "added",
            "string" to "hello", // exists
            "dimen" to "first", // a type without ids
            "string" to null,
            "id" to "new_view",
            "dimen" to "second",
            "drawable" to "new_icon",
            "string" to "added", // created already
            "attr" to "newAttr",
            "bool" to "flag", // another type without ids
            "dimen" to "third",
        )
        calls.forEach { (type, name) ->
            table.createPublicId(type, name)
            manager.createPublicId(type, name)
        }

        val expected = manager.getDefinedIds().filterKeys { it !in before }
        // In order, as the encoder declares them in order.
        assertEquals(expected.toList(), table.idsToDeclare().toList())
        // The test APK has types 0x01 to 0x08, so a type new to it is 0x09.
        assertEquals(0x7f090000, table.idsToDeclare().getValue("dimen" to "first"), "a new type gets the next type id")
        assertEquals(0x7f090001, table.idsToDeclare().getValue("dimen" to "second"))
        assertEquals(0x7f0a0000, table.idsToDeclare().getValue("bool" to "flag"), "new types get consecutive type ids")
        manager.getHighestResourceIds().forEach { (type, highest) -> assertEquals(highest, table.highestId(type)) }
    }

    @Test
    fun `replaying the calls gives a public xml manager the same ids`() = bothWays { table, manager ->
        val before = manager.getDefinedIds().keys
        table.createPublicId("string", "added")
        table.createPublicId("dimen", "first")
        table.createPublicId("bool", "flag")
        table.changePackageName("app.morphe.test")
        table.releaseTable()

        val target = PublicXmlIds(manager)
        table.replayOn(target)

        assertEquals(table.idsToDeclare(), manager.getDefinedIds().filterKeys { it !in before })
    }
}
