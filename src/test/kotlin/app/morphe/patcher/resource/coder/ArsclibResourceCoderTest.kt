/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ArsclibResourceCoderTest {

    private lateinit var workingDir: File
    private lateinit var coder: ArsclibResourceCoder

    @BeforeEach
    fun setUp(@TempDir tempDir: File) {
        workingDir = tempDir.resolve("working").also { it.mkdirs() }
        // apkFile is unused by the methods under test; just needs to exist for the constructor.
        val dummyApk = tempDir.resolve("dummy.apk").also { it.createNewFile() }
        coder = ArsclibResourceCoder(workingDir, dummyApk)
    }

    // ==================== Reflection helpers ====================

    @Suppress("UNCHECKED_CAST")
    private fun getPackageDirectories(): MutableMap<String, File> =
        coder::class.java.getDeclaredField("packageDirectories").apply { isAccessible = true }
            .get(coder) as MutableMap<String, File>

    @Suppress("UNCHECKED_CAST")
    private fun getAddedResources(): MutableSet<File> =
        coder::class.java.getDeclaredField("addedResources").apply { isAccessible = true }
            .get(coder) as MutableSet<File>

    @Suppress("UNCHECKED_CAST")
    private fun getModifiedResources(): MutableSet<File> =
        coder::class.java.getDeclaredField("modifiedResources").apply { isAccessible = true }
            .get(coder) as MutableSet<File>

    private fun setFileSnapshotCache(cache: Map<File, Any>) {
        coder::class.java.getDeclaredField("fileSnapshotCache").apply { isAccessible = true }
            .set(coder, cache)
    }

    @Suppress("UNCHECKED_CAST")
    private fun callBuildFileSnapshot(): Map<File, Any> =
        coder::class.java.getDeclaredMethod("buildFileSnapshot").apply { isAccessible = true }
            .invoke(coder) as Map<File, Any>

    private fun callDetectFileChanges() {
        coder::class.java.getDeclaredMethod("detectFileChanges").apply { isAccessible = true }
            .invoke(coder)
    }

    /**
     * Create a FileSnapshot instance via reflection (it is a private data class).
     */
    private fun createFileSnapshot(lastModified: Long, size: Long): Any {
        val snapshotClass = Class.forName("app.morphe.patcher.resource.coder.ArsclibResourceCoder\$FileSnapshot")
        return snapshotClass.getDeclaredConstructor(Long::class.java, Long::class.java)
            .apply { isAccessible = true }
            .newInstance(lastModified, size)
    }

    private fun getSnapshotLastModified(snapshot: Any): Long {
        return snapshot::class.java.getDeclaredField("lastModified").apply { isAccessible = true }
            .getLong(snapshot)
    }

    private fun getSnapshotSize(snapshot: Any): Long {
        return snapshot::class.java.getDeclaredField("size").apply { isAccessible = true }
            .getLong(snapshot)
    }

    /**
     * Set up a fake package directory structure under workingDir with a res folder.
     */
    private fun setupPackageDir(packageName: String = "com.test.app"): File {
        val pkgDir = workingDir.resolve("resources").resolve("0").also { it.mkdirs() }
        pkgDir.resolve("res").mkdirs()
        getPackageDirectories()[packageName] = pkgDir
        return pkgDir
    }

    // ==================== buildFileSnapshot tests ====================

    @Test
    fun `buildFileSnapshot captures all files in working directory`() {
        val fileA = workingDir.resolve("a.txt").also { it.writeText("hello") }
        val subDir = workingDir.resolve("sub").also { it.mkdirs() }
        val fileB = subDir.resolve("b.txt").also { it.writeText("world") }

        val snapshot = callBuildFileSnapshot()

        assertEquals(2, snapshot.size, "Snapshot should contain exactly 2 files")
        assertTrue(snapshot.containsKey(fileA), "Snapshot should contain a.txt")
        assertTrue(snapshot.containsKey(fileB), "Snapshot should contain sub/b.txt")
    }

    @Test
    fun `buildFileSnapshot records correct modification time and size`() {
        val file = workingDir.resolve("test.txt").also { it.writeText("content") }

        val snapshot = callBuildFileSnapshot()
        val entry = snapshot[file]!!

        assertEquals(file.lastModified(), getSnapshotLastModified(entry))
        assertEquals(file.length(), getSnapshotSize(entry))
    }

    @Test
    fun `buildFileSnapshot returns empty map for empty directory`() {
        val snapshot = callBuildFileSnapshot()

        assertTrue(snapshot.isEmpty(), "Snapshot should be empty for an empty working directory")
    }

    @Test
    fun `buildFileSnapshot ignores directories`() {
        workingDir.resolve("subdir").mkdirs()

        val snapshot = callBuildFileSnapshot()

        assertTrue(snapshot.isEmpty(), "Snapshot should not contain directories")
    }

    // ==================== detectFileChanges tests ====================

    @Test
    fun `detectFileChanges identifies newly added files`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")

        // Snapshot is empty (no files existed at decode time).
        setFileSnapshotCache(emptyMap())

        // Create a new file after "decoding".
        val newFile = resDir.resolve("drawable").also { it.mkdirs() }.resolve("icon.xml")
        newFile.writeText("<vector/>")

        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(added.contains(newFile), "New file should be in addedResources")
        assertTrue(modified.isEmpty(), "No files should be in modifiedResources")
    }

    @Test
    fun `detectFileChanges identifies modified files by timestamp change`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val file = resDir.resolve("values").also { it.mkdirs() }.resolve("strings.xml")
        file.writeText("<resources/>")

        // Snapshot the file with its current metadata.
        val snapshot = callBuildFileSnapshot()
        setFileSnapshotCache(snapshot)

        // Simulate a modification by changing the last modified time.
        Thread.sleep(50) // Ensure timestamp changes.
        file.setLastModified(System.currentTimeMillis() + 10_000)

        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(modified.contains(file), "Modified file should be in modifiedResources")
        assertTrue(added.isEmpty(), "No files should be in addedResources")
    }

    @Test
    fun `detectFileChanges identifies modified files by size change`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val file = resDir.resolve("values").also { it.mkdirs() }.resolve("strings.xml")
        file.writeText("short")

        // Build a snapshot with the original size.
        val originalLastModified = file.lastModified()
        val originalSize = file.length()
        val snapshotEntry = createFileSnapshot(originalLastModified, originalSize)

        setFileSnapshotCache(mapOf(file to snapshotEntry))

        // Change the content (and thus the size) but preserve the timestamp.
        file.writeText("this is a much longer string to change the file size")
        file.setLastModified(originalLastModified)

        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(modified.contains(file), "File with changed size should be in modifiedResources")
        assertTrue(added.isEmpty(), "No files should be in addedResources")
    }

    @Test
    fun `detectFileChanges ignores unchanged files`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val file = resDir.resolve("values").also { it.mkdirs() }.resolve("strings.xml")
        file.writeText("<resources/>")

        // Snapshot includes the file.
        val snapshot = callBuildFileSnapshot()
        setFileSnapshotCache(snapshot)

        // Don't change anything.
        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(added.isEmpty(), "No files should be added")
        assertTrue(modified.isEmpty(), "No files should be modified")
    }

    @Test
    fun `detectFileChanges excludes public xml from tracking`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val publicXml = resDir.resolve("values").also { it.mkdirs() }.resolve("public.xml")
        publicXml.writeText("<resources/>")

        // Empty snapshot — file would normally be "added".
        setFileSnapshotCache(emptyMap())

        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(added.isEmpty(), "public.xml should be excluded from addedResources")
        assertTrue(modified.isEmpty(), "public.xml should be excluded from modifiedResources")
    }

    @Test
    fun `detectFileChanges excludes ids xml from tracking`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val idsXml = resDir.resolve("values").also { it.mkdirs() }.resolve("ids.xml")
        idsXml.writeText("<resources/>")

        setFileSnapshotCache(emptyMap())

        callDetectFileChanges()

        val added = getAddedResources()

        assertTrue(added.isEmpty(), "ids.xml should be excluded from addedResources")
    }

    @Test
    fun `detectFileChanges handles mix of added, modified, and unchanged files`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val valuesDir = resDir.resolve("values").also { it.mkdirs() }
        val drawableDir = resDir.resolve("drawable").also { it.mkdirs() }

        // Unchanged file.
        val unchangedFile = valuesDir.resolve("colors.xml")
        unchangedFile.writeText("<resources/>")

        // File that will be modified.
        val modifiedFile = valuesDir.resolve("strings.xml")
        modifiedFile.writeText("<resources/>")

        // Build snapshot with these two files.
        val snapshot = callBuildFileSnapshot()
        setFileSnapshotCache(snapshot)

        // Modify one file.
        Thread.sleep(50)
        modifiedFile.writeText("<resources><string name=\"app\">Modified</string></resources>")
        modifiedFile.setLastModified(System.currentTimeMillis() + 10_000)

        // Add a new file.
        val addedFile = drawableDir.resolve("new_icon.xml")
        addedFile.writeText("<vector/>")

        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(added.contains(addedFile), "New file should be in addedResources")
        assertEquals(1, added.size, "Only the new file should be added")
        assertTrue(modified.contains(modifiedFile), "Modified file should be in modifiedResources")
        assertEquals(1, modified.size, "Only the modified file should be in modifiedResources")
    }

    @Test
    fun `detectFileChanges clears previous results before scanning`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val valuesDir = resDir.resolve("values").also { it.mkdirs() }

        // Pre-populate addedResources and modifiedResources with stale data.
        val staleFile = valuesDir.resolve("stale.xml").also { it.writeText("stale") }
        getAddedResources().add(staleFile)
        getModifiedResources().add(staleFile)

        // Empty snapshot, no files on disk in res (delete the stale file).
        staleFile.delete()
        setFileSnapshotCache(emptyMap())

        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(added.isEmpty(), "addedResources should be cleared")
        assertTrue(modified.isEmpty(), "modifiedResources should be cleared")
    }

    @Test
    fun `detectFileChanges scans multiple package directories`() {
        val pkgDir1 = workingDir.resolve("resources").resolve("0").also { it.mkdirs() }
        pkgDir1.resolve("res").mkdirs()
        getPackageDirectories()["com.test.app"] = pkgDir1

        val pkgDir2 = workingDir.resolve("resources").resolve("1").also { it.mkdirs() }
        pkgDir2.resolve("res").mkdirs()
        getPackageDirectories()["com.test.lib"] = pkgDir2

        setFileSnapshotCache(emptyMap())

        // Add a file in each package.
        val file1 = pkgDir1.resolve("res/drawable").also { it.mkdirs() }.resolve("a.xml")
        file1.writeText("<vector/>")
        val file2 = pkgDir2.resolve("res/drawable").also { it.mkdirs() }.resolve("b.xml")
        file2.writeText("<vector/>")

        callDetectFileChanges()

        val added = getAddedResources()

        assertTrue(added.contains(file1), "File from first package should be detected")
        assertTrue(added.contains(file2), "File from second package should be detected")
        assertEquals(2, added.size, "Both new files should be detected")
    }

    @Test
    fun `detectFileChanges only scans res subdirectory of package directories`() {
        val pkgDir = setupPackageDir()

        // Create a file outside of the res folder (e.g. package.json level).
        val nonResFile = pkgDir.resolve("some_other_file.txt")
        nonResFile.writeText("not a resource")

        setFileSnapshotCache(emptyMap())

        callDetectFileChanges()

        val added = getAddedResources()

        assertTrue(
            !added.contains(nonResFile),
            "Files outside the res directory should not be detected"
        )
    }

    @Test
    fun `detect modification of binary file change`() {
        val pkgDir = setupPackageLibDir()
        val libDir = pkgDir.resolve("lib/arm64-v8a")

        val data = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00)
        val file = libDir.resolve("libtest.so").also { it.writeBytes(data) }

        val snapshot = callBuildFileSnapshot()
        setFileSnapshotCache(snapshot)

        data[2] = 0xFF.toByte()
        file.writeBytes(data)
        // Ensure the timestamp changes — same-size writes may not update lastModified on fast filesystems.
        file.setLastModified(System.currentTimeMillis() + 10_000)

        callDetectFileChanges()

        val modified = getModifiedResources()
        assertTrue(modified.contains(file), "Binary file should be in modifiedResources")
    }

    private fun setupPackageLibDir(packageName: String = "com.test.app"): File {
        val pkgDir = workingDir.resolve("root").also { it.mkdirs() }
        pkgDir.resolve("lib/arm64-v8a").mkdirs()
        getPackageDirectories()[packageName] = pkgDir
        return pkgDir
    }

    // ==================== otherResourcesRootDirectory scanning tests ====================

    @Test
    fun `detectFileChanges identifies newly added files in otherResourcesRootDirectory`() {
        // No package directories — simulates FULL mode where files are added to root/.
        setFileSnapshotCache(emptyMap())

        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val newFile = rootDir.resolve("assets").also { it.mkdirs() }.resolve("config.json")
        newFile.writeText("{}")

        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(added.contains(newFile), "New file in root/ should be in addedResources")
        assertTrue(modified.isEmpty(), "No files should be in modifiedResources")
    }

    @Test
    fun `detectFileChanges identifies modified files in otherResourcesRootDirectory`() {
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val file = rootDir.resolve("lib").also { it.mkdirs() }.resolve("libfoo.so")
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03))

        val snapshot = callBuildFileSnapshot()
        setFileSnapshotCache(snapshot)

        // Modify the file.
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))

        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(modified.contains(file), "Modified file in root/ should be in modifiedResources")
        assertTrue(added.isEmpty(), "No files should be in addedResources")
    }

    @Test
    fun `detectFileChanges ignores unchanged files in otherResourcesRootDirectory`() {
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val file = rootDir.resolve("assets").also { it.mkdirs() }.resolve("data.bin")
        file.writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))

        val snapshot = callBuildFileSnapshot()
        setFileSnapshotCache(snapshot)

        // Don't change anything.
        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(added.isEmpty(), "No files should be added")
        assertTrue(modified.isEmpty(), "No files should be modified")
    }

    // ==================== RAW_ONLY mode tests (no packageDirectories) ====================

    @Test
    fun `detectFileChanges detects added files in RAW_ONLY mode with no package directories`() {
        // In RAW_ONLY mode, decodeRaw() does not populate packageDirectories.
        // detectFileChanges must still find new files under root/.
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }

        // Snapshot is empty — no files existed after raw decoding.
        setFileSnapshotCache(emptyMap())

        // A patch adds a new file.
        val newFile = rootDir.resolve("res").also { it.mkdirs() }
            .resolve("raw").also { it.mkdirs() }
            .resolve("patch_data.bin")
        newFile.writeBytes(byteArrayOf(0xDE.toByte(), 0xAD.toByte()))

        callDetectFileChanges()

        val added = getAddedResources()

        assertTrue(added.contains(newFile), "Added file should be detected even with empty packageDirectories")
    }

    @Test
    fun `detectFileChanges detects modified files in RAW_ONLY mode with no package directories`() {
        // In RAW_ONLY mode, decodeRaw() does not populate packageDirectories.
        // detectFileChanges must still find modified files under root/.
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val resDir = rootDir.resolve("res").also { it.mkdirs() }
        val file = resDir.resolve("raw").also { it.mkdirs() }.resolve("config.xml")
        file.writeText("<config/>")

        // Snapshot captured after decodeRaw().
        val snapshot = callBuildFileSnapshot()
        setFileSnapshotCache(snapshot)

        // A patch modifies the file.
        Thread.sleep(50)
        file.writeText("<config><entry>patched</entry></config>")
        file.setLastModified(System.currentTimeMillis() + 10_000)

        callDetectFileChanges()

        val modified = getModifiedResources()

        assertTrue(modified.contains(file), "Modified file should be detected even with empty packageDirectories")
    }

    @Test
    fun `detectFileChanges detects mix of added and modified files in RAW_ONLY mode`() {
        // Simulate RAW_ONLY: no package directories, files live under root/.
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val existingFile = rootDir.resolve("res").also { it.mkdirs() }
            .resolve("drawable").also { it.mkdirs() }
            .resolve("icon.png")
        existingFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        val snapshot = callBuildFileSnapshot()
        setFileSnapshotCache(snapshot)

        // Modify existing file.
        existingFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A))

        // Add a new file.
        val newFile = rootDir.resolve("assets").also { it.mkdirs() }.resolve("new_asset.txt")
        newFile.writeText("new content")

        callDetectFileChanges()

        val added = getAddedResources()
        val modified = getModifiedResources()

        assertTrue(added.contains(newFile), "New file under root/ should be in addedResources")
        assertTrue(modified.contains(existingFile), "Modified file under root/ should be in modifiedResources")
    }
}



