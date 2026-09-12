package app.morphe.patcher.resource

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.coder.ArsclibResourceCoder
import app.morphe.patcher.resourceLiteral
import com.android.tools.build.apkzlib.zip.ZFile
import com.reandroid.arsc.chunk.TableBlock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ResourceIdsTest {
    @AfterEach
    fun clearCurrent() {
        ResourceIds.current = null
    }

    private fun apkWithTable(tempDir: File): File {
        val table = TableBlock()
        val pkg = table.newPackage(0x7f, "com.test.app")
        pkg.getOrCreate("", "id", "spinner").setValueAsBoolean(false)
        pkg.getOrCreate("", "layout", "account_compact_link").setValueAsBoolean(false)
        pkg.getOrCreate("", "string", "app_name").setValueAsString("Test")
        table.refresh()
        val apk = tempDir.resolve("input.apk")
        ZFile.openReadWrite(apk).use { zip ->
            zip.add("resources.arsc", ByteArrayInputStream(table.bytes))
        }
        return apk
    }

    @Test
    fun `resource ids are read from the resource table`(@TempDir tempDir: File) {
        val coder = ArsclibResourceCoder(tempDir.resolve("working").apply { mkdirs() }, apkWithTable(tempDir))

        val ids = ResourceIds { coder.resourceIds() }

        assertEquals(0x7f000000L, ids[ResourceType.ID, "spinner"] and 0xff000000L)
        assertTrue(ids.has(ResourceType.LAYOUT, "account_compact_link"))
        assertTrue(ids.has(ResourceType.STRING, "app_name"))
        assertNull(ids.getOrNull(ResourceType.ID, "missing"))
        assertThrows<PatchException> { ids[ResourceType.ID, "missing"] }
        assertTrue(ids[ResourceType.ID, "spinner"] != ids[ResourceType.LAYOUT, "account_compact_link"])
    }

    @Test
    fun `an APK without a resource table has no ids`(@TempDir tempDir: File) {
        val apk = tempDir.resolve("input.apk").also { ZFile.openReadWrite(it).use { } }
        val coder = ArsclibResourceCoder(tempDir.resolve("working").apply { mkdirs() }, apk)

        assertFalse(ResourceIds { coder.resourceIds() }.has(ResourceType.ID, "spinner"))
    }

    @Test
    fun `resourceLiteral resolves the id lazily through the current APK`(@TempDir tempDir: File) {
        val coder = ArsclibResourceCoder(tempDir.resolve("working").apply { mkdirs() }, apkWithTable(tempDir))
        val ids = ResourceIds { coder.resourceIds() }
        val filter = resourceLiteral(ResourceType.ID, "spinner")
        val optional = resourceLiteral(ResourceType.ID, "missing", exceptionIfResourceNotFound = false)
        val required = resourceLiteral(ResourceType.ID, "missing")

        ResourceIds.current = ids

        assertEquals(ids[ResourceType.ID, "spinner"], filter.literalValue)
        assertNull(optional.literalValue)
        assertThrows<PatchException> { required.literalValue }
    }

    @Test
    fun `resource id lookups fail clearly outside of patching`() {
        assertThrows<PatchException> { resourceId(ResourceType.ID, "spinner") }
        assertThrows<PatchException> { hasResourceId(ResourceType.ID, "spinner") }
    }

    @Test
    fun `resource types map from their names`() {
        assertEquals(ResourceType.LAYOUT, ResourceType.fromValue("layout"))
        assertNull(ResourceType.fromValueOrNull("nope"))
        assertThrows<IllegalArgumentException> { ResourceType.fromValue("nope") }
    }

    @Test
    fun `real APK table when available`(@TempDir tempDir: File) {
        val apk = System.getenv("MORPHE_TEST_APK")?.let(::File)?.takeIf { it.isFile } ?: return
        val coder = ArsclibResourceCoder(tempDir.resolve("working").apply { mkdirs() }, apk)

        val ids = ResourceIds { coder.resourceIds() }

        assertTrue(ids.has(ResourceType.ID, "spinner"))
        assertTrue(ids.has(ResourceType.LAYOUT, "account_compact_link"))
    }
}
