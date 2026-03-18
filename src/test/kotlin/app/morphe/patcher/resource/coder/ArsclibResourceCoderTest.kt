/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.resource.CpuArchitecture
import app.morphe.patcher.resource.PathMap
import app.morphe.patcher.resource.ResourceMode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * Set up a fake package directory structure under workingDir with a res folder.
     */
    private fun setupPackageDir(packageName: String = "com.test.app"): File {
        val pkgDir = workingDir.resolve("resources").resolve("0").also { it.mkdirs() }
        pkgDir.resolve("res").mkdirs()
        coder.packageDirectories[packageName] = pkgDir
        return pkgDir
    }

    // ==================== buildFileSnapshot tests ====================

    @Test
    fun `buildFileSnapshot captures all files in working directory`() {
        val pkgDir = setupPackageDir()
        val fileA = pkgDir.resolve("a.txt").also { it.writeText("hello") }
        val subDir = pkgDir.resolve("sub").also { it.mkdirs() }
        val fileB = subDir.resolve("b.txt").also { it.writeText("world") }

        val snapshot = coder.buildFileSnapshot()

        assertEquals(2, snapshot.size, "Snapshot should contain exactly 2 files")
        assertTrue(snapshot.containsKey(fileA), "Snapshot should contain a.txt")
        assertTrue(snapshot.containsKey(fileB), "Snapshot should contain sub/b.txt")
    }

    @Test
    fun `buildFileSnapshot records correct modification time and size`() {
        val pkgDir = setupPackageDir()
        val file = pkgDir.resolve("test.txt").also { it.writeText("content") }

        val snapshot = coder.buildFileSnapshot()
        val entry = snapshot[file]!!

        assertEquals(file.lastModified(), entry.lastModified)
        assertEquals(file.length(), entry.size)
    }

    @Test
    fun `buildFileSnapshot returns empty map for empty directory`() {
        val snapshot = coder.buildFileSnapshot()

        assertTrue(snapshot.isEmpty(), "Snapshot should be empty for an empty working directory")
    }

    @Test
    fun `buildFileSnapshot ignores directories`() {
        workingDir.resolve("subdir").mkdirs()

        val snapshot = coder.buildFileSnapshot()

        assertTrue(snapshot.isEmpty(), "Snapshot should not contain directories")
    }

    // ==================== detectFileChanges tests ====================

    @Test
    fun `detectFileChanges identifies newly added files`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")

        // Snapshot is empty (no files existed at decode time).
        coder.fileSnapshotCache = emptyMap()

        // Create a new file after "decoding".
        val newFile = resDir.resolve("drawable").also { it.mkdirs() }.resolve("icon.xml")
        newFile.writeText("<vector/>")

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.contains(newFile), "New file should be in modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.isEmpty(), "No files should be in modifiedBinaryResources")
    }

    @Test
    fun `detectFileChanges identifies modified files by timestamp change`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val file = resDir.resolve("values").also { it.mkdirs() }.resolve("strings.xml")
        file.writeText("<resources/>")

        // Snapshot the file with its current metadata.
        val snapshot = coder.buildFileSnapshot()
        coder.fileSnapshotCache = snapshot

        // Simulate a modification by changing the last modified time.
        Thread.sleep(50) // Ensure timestamp changes.
        file.setLastModified(System.currentTimeMillis() + 10_000)

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.contains(file), "Modified file should be in modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.isEmpty(), "No files should be in modifiedBinaryResources")
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
        val snapshotEntry = ArsclibResourceCoder.FileSnapshot(originalLastModified, originalSize)

        coder.fileSnapshotCache = mapOf(file to snapshotEntry)

        // Change the content (and thus the size) but preserve the timestamp.
        file.writeText("this is a much longer string to change the file size")
        file.setLastModified(originalLastModified)

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.contains(file), "Modified file should be in modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.isEmpty(), "No files should be in modifiedBinaryResources")
    }

    @Test
    fun `detectFileChanges ignores unchanged files`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val file = resDir.resolve("values").also { it.mkdirs() }.resolve("strings.xml")
        file.writeText("<resources/>")

        // Snapshot includes the file.
        val snapshot = coder.buildFileSnapshot()
        coder.fileSnapshotCache = snapshot

        // Don't change anything.
        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.isEmpty(), "No files should be in modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.isEmpty(), "No files should be in modifiedBinaryResources")
    }

    @Test
    fun `detectFileChanges excludes public xml from tracking`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val publicXml = resDir.resolve("values").also { it.mkdirs() }.resolve("public.xml")
        publicXml.writeText("<resources/>")

        // Empty snapshot — file would normally be "added".
        coder.fileSnapshotCache = emptyMap()

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.isEmpty(), "public.xml should be excluded from modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.isEmpty(), "public.xml should be excluded from modifiedBinaryResources")
    }

    @Test
    fun `detectFileChanges excludes ids xml from tracking`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val idsXml = resDir.resolve("values").also { it.mkdirs() }.resolve("ids.xml")
        idsXml.writeText("<resources/>")

        coder.fileSnapshotCache = emptyMap()

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.isEmpty(), "ids.xml should be excluded from modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.isEmpty(), "ids.xml should be excluded from modifiedBinaryResources")
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
        val snapshot = coder.buildFileSnapshot()
        coder.fileSnapshotCache = snapshot

        // Modify one file.
        Thread.sleep(50)
        modifiedFile.writeText("<resources><string name=\"app\">Modified</string></resources>")
        modifiedFile.setLastModified(System.currentTimeMillis() + 10_000)

        // Add a new file.
        val addedFile = drawableDir.resolve("new_icon.xml")
        addedFile.writeText("<vector/>")

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.contains(addedFile), "New file should be in modifiedResResources")
        assertTrue(coder.modifiedResResources.contains(modifiedFile), "Modified file should be in modifiedResResources")
        assertEquals(2, coder.modifiedResResources.size, "Both files should be in modifiedResResources")
        assertEquals(0, coder.modifiedBinaryResources.size, "There should be no file in modifiedBinaryResources")
    }

    @Test
    fun `detectFileChanges clears previous results before scanning`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val valuesDir = resDir.resolve("values").also { it.mkdirs() }

        // Pre-populate addedResources and modifiedResources with stale data.
        val staleFile = valuesDir.resolve("stale.xml").also { it.writeText("stale") }
        coder.modifiedResResources.add(staleFile)
        coder.modifiedBinaryResources.add(staleFile)

        // Empty snapshot, no files on disk in res (delete the stale file).
        staleFile.delete()
        coder.fileSnapshotCache = emptyMap()

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.isEmpty(), "modifiedResResources should be cleared")
        assertTrue(coder.modifiedBinaryResources.isEmpty(), "modifiedBinaryResources should be cleared")
    }

    @Test
    fun `detectFileChanges scans multiple package directories`() {
        val pkgDir1 = workingDir.resolve("resources").resolve("0").also { it.mkdirs() }
        pkgDir1.resolve("res").mkdirs()
        coder.packageDirectories["com.test.app"] = pkgDir1

        val pkgDir2 = workingDir.resolve("resources").resolve("1").also { it.mkdirs() }
        pkgDir2.resolve("res").mkdirs()
        coder.packageDirectories["com.test.lib"] = pkgDir2

        coder.fileSnapshotCache = emptyMap()

        // Add a file in each package.
        val file1 = pkgDir1.resolve("res/drawable").also { it.mkdirs() }.resolve("a.xml")
        file1.writeText("<vector/>")
        val file2 = pkgDir2.resolve("res/drawable").also { it.mkdirs() }.resolve("b.xml")
        file2.writeText("<vector/>")

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.contains(file1), "File from first package should be detected")
        assertTrue(coder.modifiedResResources.contains(file2), "File from second package should be detected")
        assertEquals(2, coder.modifiedResResources.size, "Both new files should be detected")
        assertEquals(0, coder.modifiedBinaryResources.size, "No files should be in modifiedBinaryResources")
    }

    @Test
    fun `detectFileChanges only scans res subdirectory of package directories`() {
        val pkgDir = setupPackageDir()

        // Create a file outside of the res folder (e.g. package.json level).
        val nonResFile = pkgDir.resolve("some_other_file.txt")
        nonResFile.writeText("not a resource")

        coder.fileSnapshotCache = emptyMap()

        coder.detectFileChanges()

        assertTrue(
            !coder.modifiedResResources.contains(nonResFile),
            "Files outside the res directory should not be detected"
        )

        assertTrue(
            !coder.modifiedBinaryResources.contains(nonResFile),
            "Files outside the res directory should not be detected"
        )
    }

    @Test
    fun `detect modification of binary file change`() {
        val pkgDir = setupPackageLibDir()
        val libDir = pkgDir.resolve("lib/arm64-v8a")

        val data = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00)
        val file = libDir.resolve("libtest.so").also { it.writeBytes(data) }

        val snapshot = coder.buildFileSnapshot()
        coder.fileSnapshotCache = snapshot

        data[2] = 0xFF.toByte()
        file.writeBytes(data)
        // Ensure the timestamp changes — same-size writes may not update lastModified on fast filesystems.
        file.setLastModified(System.currentTimeMillis() + 10_000)

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.isEmpty(), "Binary file should not be in modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.contains(file), "Binary file should be in modifiedBinaryResources")
    }

    private fun setupPackageLibDir(packageName: String = "com.test.app"): File {
        val pkgDir = workingDir.resolve("root").also { it.mkdirs() }
        pkgDir.resolve("lib/arm64-v8a").mkdirs()
        coder.packageDirectories[packageName] = pkgDir
        return pkgDir
    }

    // ==================== otherResourcesRootDirectory scanning tests ====================

    @Test
    fun `detectFileChanges identifies newly added files in otherResourcesRootDirectory`() {
        // No package directories — simulates FULL mode where files are added to root/.
        coder.fileSnapshotCache = emptyMap()

        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val newFile = rootDir.resolve("assets").also { it.mkdirs() }.resolve("config.json")
        newFile.writeText("{}")

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.isEmpty(), "Binary file should not be in modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.contains(newFile), "New file in root/ should be in modifiedBinaryResources")
    }

    @Test
    fun `detectFileChanges identifies modified files in otherResourcesRootDirectory`() {
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val file = rootDir.resolve("lib").also { it.mkdirs() }.resolve("libfoo.so")
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03))

        val snapshot = coder.buildFileSnapshot()
        coder.fileSnapshotCache = snapshot

        // Modify the file.
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.isEmpty(), "Binary file should not be in modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.contains(file), "New file in root/ should be in modifiedBinaryResources")
    }

    @Test
    fun `detectFileChanges ignores unchanged files in otherResourcesRootDirectory`() {
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val file = rootDir.resolve("assets").also { it.mkdirs() }.resolve("data.bin")
        file.writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))

        val snapshot = coder.buildFileSnapshot()
        coder.fileSnapshotCache = snapshot

        // Don't change anything.
        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.isEmpty(), "Binary file should not be in modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.isEmpty(), "Binary file should not be in modifiedBinaryResources")
    }

    // ==================== RAW_ONLY mode tests (no packageDirectories) ====================

    @Test
    fun `detectFileChanges detects added files in RAW_ONLY mode with no package directories`() {
        // In RAW_ONLY mode, decodeRaw() does not populate packageDirectories.
        // detectFileChanges must still find new files under root/.
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }

        // Snapshot is empty — no files existed after raw decoding.
        coder.fileSnapshotCache = emptyMap()

        // A patch adds a new file.
        val newFile = rootDir.resolve("raw").also { it.mkdirs() }
            .resolve("patch_data.bin")
        newFile.writeBytes(byteArrayOf(0xDE.toByte(), 0xAD.toByte()))

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.isEmpty(), "Binary file should not be in modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.contains(newFile), "Added file should be detected even with empty packageDirectories")
    }

    @Test
    fun `detectFileChanges detects modified files in RAW_ONLY mode with no package directories`() {
        // In RAW_ONLY mode, decodeRaw() does not populate packageDirectories.
        // detectFileChanges must still find modified files under root/.
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val file = rootDir.resolve("raw").also { it.mkdirs() }.resolve("config.xml")
        file.writeText("<config/>")

        // Snapshot captured after decodeRaw().
        val snapshot = coder.buildFileSnapshot()
        coder.fileSnapshotCache = snapshot

        // A patch modifies the file.
        Thread.sleep(50)
        file.writeText("<config><entry>patched</entry></config>")
        file.setLastModified(System.currentTimeMillis() + 10_000)

        coder.detectFileChanges()

        assertTrue(coder.modifiedBinaryResources.contains(file), "Modified file should be detected even with empty packageDirectories")
    }

    @Test
    fun `detectFileChanges detects mix of added and modified files in RAW_ONLY mode`() {
        // Simulate RAW_ONLY: no package directories, files live under root/.
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val existingFile = rootDir.resolve("raw").also { it.mkdirs() }
            .resolve("icon.png")
        existingFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        val snapshot = coder.buildFileSnapshot()
        coder.fileSnapshotCache = snapshot

        // Modify existing file.
        existingFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A))

        // Add a new file.
        val newFile = rootDir.resolve("assets").also { it.mkdirs() }.resolve("new_asset.txt")
        newFile.writeText("new content")

        coder.detectFileChanges()

        assertTrue(coder.modifiedResResources.isEmpty(), "Binary file should not be in modifiedResResources")
        assertTrue(coder.modifiedBinaryResources.contains(newFile), "New file under root/ should be in modifiedBinaryResources")
        assertTrue(coder.modifiedBinaryResources.contains(existingFile), "Modified file under root/ should be in modifiedBinaryResources")
    }

    // ==================== Path separator tests ====================

    /**
     * Verify that excludedPaths matching uses invariantSeparatorsPath (forward slashes)
     * so that it works on both Unix and Windows. On Windows, File.relativeTo() would
     * produce backslash-separated paths like "res\values\public.xml", which would fail
     * to match the forward-slash entries in excludedPaths without normalization.
     */
    @Test
    fun `detectFileChanges excludes paths using forward slash comparison regardless of platform separator`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val valuesDir = resDir.resolve("values").also { it.mkdirs() }

        // Create all excluded files
        val publicXml = valuesDir.resolve("public.xml").also { it.writeText("<resources/>") }
        val idsXml = valuesDir.resolve("ids.xml").also { it.writeText("<resources/>") }
        val manifestXml = pkgDir.resolve("AndroidManifest.xml").also { it.writeText("<manifest/>") }

        // Also create a non-excluded file
        val stringsXml = valuesDir.resolve("strings.xml").also { it.writeText("<resources/>") }

        coder.fileSnapshotCache = emptyMap()
        coder.detectFileChanges()

        // Verify excluded files are NOT detected
        assertFalse(
            coder.modifiedResResources.contains(publicXml),
            "public.xml should be excluded via invariantSeparatorsPath matching"
        )
        assertFalse(
            coder.modifiedResResources.contains(idsXml),
            "ids.xml should be excluded via invariantSeparatorsPath matching"
        )

        // Verify that the relativeTo().invariantSeparatorsPath output matches
        // the format in excludedPaths (forward slashes)
        val relativePublicPath = publicXml.relativeTo(pkgDir).invariantSeparatorsPath
        assertEquals("res/values/public.xml", relativePublicPath,
            "Relative path should use forward slashes regardless of platform")

        val relativeIdsPath = idsXml.relativeTo(pkgDir).invariantSeparatorsPath
        assertEquals("res/values/ids.xml", relativeIdsPath,
            "Relative path should use forward slashes regardless of platform")

        // Non-excluded files should still be detected
        assertTrue(
            coder.modifiedResResources.contains(stringsXml),
            "strings.xml should NOT be excluded"
        )
    }

    /**
     * Verify that the path stripping logic in getOtherResourceFiles (RAW_ONLY mode)
     * correctly strips the working directory prefix from file paths using
     * invariantSeparatorsPath (forward slashes). On Windows, absolutePath would
     * contain backslashes, causing the .replace() call to fail without normalization.
     */
    @Test
    fun `path stripping uses invariantSeparatorsPath for consistent results across platforms`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val drawableDir = resDir.resolve("drawable").also { it.mkdirs() }
        val newFile = drawableDir.resolve("icon.xml").also { it.writeText("<vector/>") }

        // Simulate what getOtherResourceFiles does for modifiedResResources:
        // val workingDirPath = workingDir.absoluteFile.invariantSeparatorsPath
        // val path = it.absoluteFile.invariantSeparatorsPath.replace(workingDirPath, "")
        // val subPath = path.substringAfter("/resources/").substringAfter("/")
        val workingDirPath = workingDir.absoluteFile.invariantSeparatorsPath
        val filePath = newFile.absoluteFile.invariantSeparatorsPath
        val strippedPath = filePath.replace(workingDirPath, "")
        val subPath = strippedPath.substringAfter("/resources/").substringAfter("/")

        assertEquals("res/drawable/icon.xml", subPath,
            "Path stripping should produce a clean forward-slash relative path")

        // Verify that without invariantSeparatorsPath, the paths would still be
        // consistent on this platform (they are, but on Windows they wouldn't be).
        assertTrue(filePath.startsWith(workingDirPath),
            "File path should start with working directory path when using invariantSeparatorsPath")
    }

    /**
     * Verify that the path stripping logic for modifiedBinaryResources in
     * getOtherResourceFiles correctly strips the "/root/" prefix.
     */
    @Test
    fun `binary resource path stripping removes root prefix using forward slashes`() {
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val libDir = rootDir.resolve("lib/arm64-v8a").also { it.mkdirs() }
        val soFile = libDir.resolve("libtest.so").also { it.writeBytes(byteArrayOf(0x00)) }

        // Simulate the path stripping from getOtherResourceFiles for modifiedBinaryResources:
        // val workingDirPath = workingDir.absoluteFile.invariantSeparatorsPath
        // val path = it.absoluteFile.invariantSeparatorsPath.replace(workingDirPath, "")
        // otherFiles[it] = otherResourcesDir.resolve(path.replace("/root/", ""))
        val workingDirPath = workingDir.absoluteFile.invariantSeparatorsPath
        val filePath = soFile.absoluteFile.invariantSeparatorsPath
        val strippedPath = filePath.replace(workingDirPath, "")
        val finalPath = strippedPath.replace("/root/", "")

        assertEquals("lib/arm64-v8a/libtest.so", finalPath,
            "Binary path stripping should remove working dir and /root/ prefix using forward slashes")
    }

    /**
     * Verify that the path stripping logic for modifiedResResources handles
     * deeply nested directory structures correctly.
     */
    @Test
    fun `path stripping handles deeply nested resource directories`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val deepDir = resDir.resolve("values-en-rUS").also { it.mkdirs() }
        val file = deepDir.resolve("strings.xml").also { it.writeText("<resources/>") }

        val workingDirPath = workingDir.absoluteFile.invariantSeparatorsPath
        val filePath = file.absoluteFile.invariantSeparatorsPath
        val strippedPath = filePath.replace(workingDirPath, "")
        val subPath = strippedPath.substringAfter("/resources/").substringAfter("/")

        assertEquals("res/values-en-rUS/strings.xml", subPath,
            "Path stripping should handle qualifier directories correctly")
    }

    /**
     * Verify that the path stripping logic for binary resources handles
     * deeply nested paths under root/ correctly.
     */
    @Test
    fun `binary path stripping handles nested asset directories`() {
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val assetDir = rootDir.resolve("assets/data/config").also { it.mkdirs() }
        val file = assetDir.resolve("settings.json").also { it.writeText("{}") }

        val workingDirPath = workingDir.absoluteFile.invariantSeparatorsPath
        val filePath = file.absoluteFile.invariantSeparatorsPath
        val strippedPath = filePath.replace(workingDirPath, "")
        val finalPath = strippedPath.replace("/root/", "")

        assertEquals("assets/data/config/settings.json", finalPath,
            "Binary path stripping should preserve nested directory structure under root/")
    }

    // ==================== getFile PathMap aliasing tests ====================

    @Test
    fun `getFile resolves aliased res path when path map has mapping`() {
        val pkgDir = setupPackageDir()

        // On disk the file lives at the alias path
        val aliasDir = pkgDir.resolve("res/drawable-mdpi").also { it.mkdirs() }
        val aliasFile = aliasDir.resolve("drawable_0x7f080695.png").also { it.writeText("PNG") }

        coder.pathMap = PathMap("""[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]""")

        // A caller asks for the original APK name
        val result = coder.getFile("res/-5N.png", packageName = "com.test.app")

        assertEquals(aliasFile.absolutePath, result.absolutePath,
            "getFile should resolve the original name to the on-disk alias via the path map")
    }

    @Test
    fun `getFile resolves unmapped res path directly`() {
        val pkgDir = setupPackageDir()

        val valuesDir = pkgDir.resolve("res/values").also { it.mkdirs() }
        val stringsFile = valuesDir.resolve("strings.xml").also { it.writeText("<resources/>") }

        coder.pathMap = PathMap("""[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]""")

        // "res/values/strings.xml" has no path map entry — should resolve as-is
        val result = coder.getFile("res/values/strings.xml", packageName = "com.test.app")

        assertEquals(stringsFile.absolutePath, result.absolutePath,
            "getFile should resolve unmapped paths directly without aliasing")
    }

    @Test
    fun `getFile resolves aliased path already using alias name`() {
        val pkgDir = setupPackageDir()

        val aliasDir = pkgDir.resolve("res/drawable-mdpi").also { it.mkdirs() }
        val aliasFile = aliasDir.resolve("drawable_0x7f080695.png").also { it.writeText("PNG") }

        coder.pathMap = PathMap("""[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]""")

        // A caller directly asks for the alias path — getAlias returns null so it resolves as-is
        val result = coder.getFile("res/drawable-mdpi/drawable_0x7f080695.png", packageName = "com.test.app")

        assertEquals(aliasFile.absolutePath, result.absolutePath,
            "getFile should still work when called with the alias path directly")
    }

    @Test
    fun `getFile resolves AndroidManifest without aliasing`() {
        val manifest = workingDir.resolve("AndroidManifest.xml").also { it.writeText("<manifest/>") }

        coder.pathMap = PathMap("""[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]""")

        val result = coder.getFile("AndroidManifest.xml", packageName = "com.test.app")

        assertEquals(manifest.absolutePath, result.absolutePath,
            "AndroidManifest.xml should resolve to the working directory")
    }

    @Test
    fun `getFile resolves aliased root file via path map`() {
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val aliasDir = rootDir.resolve("assets/data").also { it.mkdirs() }
        val aliasFile = aliasDir.resolve("config_aliased.bin").also { it.writeBytes(byteArrayOf(0x01)) }

        coder.pathMap = PathMap("""[
            {"name": "assets/config.bin", "alias": "assets/data/config_aliased.bin"}
        ]""")

        val result = coder.getFile("assets/config.bin", packageName = "com.test.app")

        assertEquals(aliasFile.absolutePath, result.absolutePath,
            "getFile should resolve root files via path map alias")
    }

    @Test
    fun `getFile with empty path map resolves paths directly`() {
        val pkgDir = setupPackageDir()
        val drawableDir = pkgDir.resolve("res/drawable").also { it.mkdirs() }
        val file = drawableDir.resolve("icon.png").also { it.writeText("PNG") }

        coder.pathMap = PathMap.EMPTY

        val result = coder.getFile("res/drawable/icon.png", packageName = "com.test.app")

        assertEquals(file.absolutePath, result.absolutePath,
            "getFile with empty path map should resolve paths directly")
    }

    // ==================== getOtherResourceFiles unaliasing tests ====================

    @Test
    fun `getOtherResourceFiles unaliases modified res resource paths in RAW_ONLY mode`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val drawableDir = resDir.resolve("drawable-mdpi").also { it.mkdirs() }
        val aliasFile = drawableDir.resolve("drawable_0x7f080695.png").also { it.writeText("PNG") }

        coder.fileSnapshotCache = emptyMap()
        coder.pathMap = PathMap("""[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]""")

        val outputDir = workingDir.resolveSibling("output").also { it.mkdirs() }
        val result = coder.getOtherResourceFiles(outputDir, ResourceMode.RAW_ONLY)!!

        // The file should be moved to the output dir under the original APK name
        val expectedFile = result.resolve("res/-5N.png")
        assertTrue(expectedFile.exists(),
            "Modified res file should be moved to the original APK name path, not the alias. " +
            "Contents of output: ${result.walkTopDown().filter { it.isFile }.map { it.relativeTo(result).path }.toList()}")
    }

    @Test
    fun `getOtherResourceFiles unaliases modified binary resource paths in RAW_ONLY mode`() {
        setupPackageDir() // Need at least one package dir for the method to work
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val aliasDir = rootDir.resolve("assets/data").also { it.mkdirs() }
        aliasDir.resolve("config_aliased.bin").also { it.writeBytes(byteArrayOf(0x01)) }

        coder.fileSnapshotCache = emptyMap()
        coder.pathMap = PathMap("""[
            {"name": "assets/config.bin", "alias": "assets/data/config_aliased.bin"}
        ]""")

        val outputDir = workingDir.resolveSibling("output").also { it.mkdirs() }
        val result = coder.getOtherResourceFiles(outputDir, ResourceMode.RAW_ONLY)!!

        val expectedFile = result.resolve("assets/config.bin")
        assertTrue(expectedFile.exists(),
            "Modified binary file should be moved to the original APK name path, not the alias. " +
            "Contents of output: ${result.walkTopDown().filter { it.isFile }.map { it.relativeTo(result).path }.toList()}")
    }

    @Test
    fun `getOtherResourceFiles preserves unmapped paths in RAW_ONLY mode`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val valuesDir = resDir.resolve("values").also { it.mkdirs() }
        valuesDir.resolve("strings.xml").also { it.writeText("<resources/>") }

        coder.fileSnapshotCache = emptyMap()
        coder.pathMap = PathMap("""[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]""")

        val outputDir = workingDir.resolveSibling("output").also { it.mkdirs() }
        val result = coder.getOtherResourceFiles(outputDir, ResourceMode.RAW_ONLY)!!

        // strings.xml has no path map entry — should keep its path as-is
        val expectedFile = result.resolve("res/values/strings.xml")
        assertTrue(expectedFile.exists(),
            "Unmapped file should keep its original path. " +
            "Contents of output: ${result.walkTopDown().filter { it.isFile }.map { it.relativeTo(result).path }.toList()}")
    }

    @Test
    fun `getOtherResourceFiles handles mix of mapped and unmapped paths in RAW_ONLY mode`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")

        // Mapped file (alias on disk)
        val drawableDir = resDir.resolve("drawable-mdpi").also { it.mkdirs() }
        drawableDir.resolve("drawable_0x7f080695.png").also { it.writeText("PNG") }

        // Unmapped file (same name on disk and in APK)
        val valuesDir = resDir.resolve("values").also { it.mkdirs() }
        valuesDir.resolve("strings.xml").also { it.writeText("<resources/>") }

        // Unmapped binary file
        val rootDir = workingDir.resolve("root").also { it.mkdirs() }
        val libDir = rootDir.resolve("lib/arm64-v8a").also { it.mkdirs() }
        libDir.resolve("libfoo.so").also { it.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46)) }

        coder.fileSnapshotCache = emptyMap()
        coder.pathMap = PathMap("""[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]""")

        val outputDir = workingDir.resolveSibling("output").also { it.mkdirs() }
        val result = coder.getOtherResourceFiles(outputDir, ResourceMode.RAW_ONLY)!!

        val outputFiles = result.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(result).invariantSeparatorsPath }.toSet()

        assertTrue("res/-5N.png" in outputFiles,
            "Mapped res file should be unaliased to original APK name. Output: $outputFiles")
        assertTrue("res/values/strings.xml" in outputFiles,
            "Unmapped res file should keep its path. Output: $outputFiles")
        assertTrue("lib/arm64-v8a/libfoo.so" in outputFiles,
            "Unmapped binary file should keep its path. Output: $outputFiles")
    }

    @Test
    fun `getOtherResourceFiles with empty path map preserves all paths in RAW_ONLY mode`() {
        val pkgDir = setupPackageDir()
        val resDir = pkgDir.resolve("res")
        val drawableDir = resDir.resolve("drawable").also { it.mkdirs() }
        drawableDir.resolve("icon.png").also { it.writeText("PNG") }

        coder.fileSnapshotCache = emptyMap()
        coder.pathMap = PathMap.EMPTY

        val outputDir = workingDir.resolveSibling("output").also { it.mkdirs() }
        val result = coder.getOtherResourceFiles(outputDir, ResourceMode.RAW_ONLY)!!

        val expectedFile = result.resolve("res/drawable/icon.png")
        assertTrue(expectedFile.exists(),
            "With empty path map, files should keep their on-disk paths")
    }

    // ==================== Native library removal tests ====================

    /**
     * Helper to create a coder with specific keepArchitectures.
     */
    private fun createCoderWithKeepArchitectures(
        tempDir: File,
        keepArchitectures: Set<CpuArchitecture>
    ): ArsclibResourceCoder {
        val dummyApk = tempDir.resolve("dummy2.apk").also { it.createNewFile() }
        return ArsclibResourceCoder(workingDir, dummyApk, keepArchitectures)
    }

    /**
     * Helper to set up native library directories under root/lib/.
     * Creates .so files in each specified architecture directory.
     */
    private fun setupNativeLibDirs(
        architectures: List<String>,
        filesPerArch: Int = 2
    ): Map<String, List<File>> {
        val rootLibDir = workingDir.resolve("root/lib").also { it.mkdirs() }
        val result = mutableMapOf<String, List<File>>()

        architectures.forEach { arch ->
            val archDir = rootLibDir.resolve(arch).also { it.mkdirs() }
            val files = (1..filesPerArch).map { i ->
                archDir.resolve("lib$arch$i.so").also {
                    it.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, i.toByte()))
                }
            }
            result[arch] = files
        }

        return result
    }

    @Test
    fun `stripNativeLibraries removes architectures not in keepArchitectures`(@TempDir tempDir: File) {
        val archCoder = createCoderWithKeepArchitectures(
            tempDir, setOf(CpuArchitecture.ARM64_V8A)
        )

        // Set up libs for arm64-v8a, x86, and x86_64.
        setupNativeLibDirs(listOf("arm64-v8a", "x86", "x86_64"))

        // Simulate the decode snapshot — all files exist.
        archCoder.packageDirectories.putAll(coder.packageDirectories)
        archCoder.fileSnapshotCache = archCoder.buildFileSnapshot()

        // Call stripNativeLibraries.
        archCoder.stripNativeLibraries()

        // arm64-v8a should be kept.
        val rootLibDir = workingDir.resolve("root/lib")
        assertTrue(rootLibDir.resolve("arm64-v8a").exists(),
            "arm64-v8a directory should be kept")
        assertTrue(rootLibDir.resolve("arm64-v8a").listFiles()!!.isNotEmpty(),
            "arm64-v8a should still contain files")

        // x86 and x86_64 should be removed.
        assertFalse(rootLibDir.resolve("x86").exists(),
            "x86 directory should be removed")
        assertFalse(rootLibDir.resolve("x86_64").exists(),
            "x86_64 directory should be removed")
    }

    @Test
    fun `stripNativeLibraries keeps multiple architectures`(@TempDir tempDir: File) {
        val archCoder = createCoderWithKeepArchitectures(
            tempDir, setOf(CpuArchitecture.ARM64_V8A, CpuArchitecture.ARMEABI_V7A)
        )

        setupNativeLibDirs(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))

        archCoder.packageDirectories.putAll(coder.packageDirectories)
        archCoder.fileSnapshotCache = archCoder.buildFileSnapshot()

        archCoder.stripNativeLibraries()

        val rootLibDir = workingDir.resolve("root/lib")
        assertTrue(rootLibDir.resolve("arm64-v8a").exists(),
            "arm64-v8a should be kept")
        assertTrue(rootLibDir.resolve("armeabi-v7a").exists(),
            "armeabi-v7a should be kept")
        assertFalse(rootLibDir.resolve("x86").exists(),
            "x86 should be removed")
        assertFalse(rootLibDir.resolve("x86_64").exists(),
            "x86_64 should be removed")
    }

    @Test
    fun `stripNativeLibraries is no-op when keepArchitectures is empty`() {
        // Default coder has empty keepArchitectures.
        setupNativeLibDirs(listOf("arm64-v8a", "x86", "x86_64"))

        coder.stripNativeLibraries()

        val rootLibDir = workingDir.resolve("root/lib")
        assertTrue(rootLibDir.resolve("arm64-v8a").exists(),
            "arm64-v8a should remain when keepArchitectures is empty")
        assertTrue(rootLibDir.resolve("x86").exists(),
            "x86 should remain when keepArchitectures is empty")
        assertTrue(rootLibDir.resolve("x86_64").exists(),
            "x86_64 should remain when keepArchitectures is empty")
    }

    @Test
    fun `stripNativeLibraries handles missing lib directory gracefully`(@TempDir tempDir: File) {
        val archCoder = createCoderWithKeepArchitectures(
            tempDir, setOf(CpuArchitecture.ARM64_V8A)
        )

        // Don't create any lib directory.
        archCoder.stripNativeLibraries()

        // Should not throw.
        assertFalse(workingDir.resolve("root/lib").exists(),
            "No lib directory should exist")
    }

    @Test
    fun `stripNativeLibraries removes all architectures when none match keepArchitectures`(@TempDir tempDir: File) {
        val archCoder = createCoderWithKeepArchitectures(
            tempDir, setOf(CpuArchitecture.MIPS)
        )

        setupNativeLibDirs(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))

        archCoder.stripNativeLibraries()

        val rootLibDir = workingDir.resolve("root/lib")
        assertFalse(rootLibDir.resolve("arm64-v8a").exists(), "arm64-v8a should be removed")
        assertFalse(rootLibDir.resolve("armeabi-v7a").exists(), "armeabi-v7a should be removed")
        assertFalse(rootLibDir.resolve("x86").exists(), "x86 should be removed")
        assertFalse(rootLibDir.resolve("x86_64").exists(), "x86_64 should be removed")
    }

    @Test
    fun `stripNativeLibraries keeps all architectures when all match`(@TempDir tempDir: File) {
        val archCoder = createCoderWithKeepArchitectures(
            tempDir, setOf(CpuArchitecture.ARM64_V8A, CpuArchitecture.X86)
        )

        setupNativeLibDirs(listOf("arm64-v8a", "x86"))

        archCoder.stripNativeLibraries()

        val rootLibDir = workingDir.resolve("root/lib")
        assertTrue(rootLibDir.resolve("arm64-v8a").exists(), "arm64-v8a should be kept")
        assertTrue(rootLibDir.resolve("x86").exists(), "x86 should be kept")
    }

    @Test
    fun `stripNativeLibraries ignores non-architecture directories under lib`(@TempDir tempDir: File) {
        val archCoder = createCoderWithKeepArchitectures(
            tempDir, setOf(CpuArchitecture.ARM64_V8A)
        )

        setupNativeLibDirs(listOf("arm64-v8a", "x86"))

        // Create a non-architecture directory under lib.
        val nonArchDir = workingDir.resolve("root/lib/some-other-dir").also { it.mkdirs() }
        nonArchDir.resolve("somefile.txt").writeText("not a native lib")

        archCoder.stripNativeLibraries()

        val rootLibDir = workingDir.resolve("root/lib")
        assertTrue(rootLibDir.resolve("arm64-v8a").exists(), "arm64-v8a should be kept")
        assertFalse(rootLibDir.resolve("x86").exists(), "x86 should be removed")
        // Non-architecture directories are not recognized as a CpuArchitecture,
        // so valueOfOrNull returns null and null !in keepArchitectures == true,
        // meaning they get removed.
        assertFalse(rootLibDir.resolve("some-other-dir").exists(),
            "Unrecognized directories under lib/ should be removed")
    }

    @Test
    fun `stripped native libraries do not appear in detectFileChanges`(@TempDir tempDir: File) {
        val archCoder = createCoderWithKeepArchitectures(
            tempDir, setOf(CpuArchitecture.ARM64_V8A)
        )

        val libFiles = setupNativeLibDirs(listOf("arm64-v8a", "x86"))

        // Simulate decode: capture snapshot of all files.
        archCoder.fileSnapshotCache = archCoder.buildFileSnapshot()

        // Strip non-kept architectures.
        archCoder.stripNativeLibraries()

        // Detect changes after stripping.
        archCoder.detectFileChanges()

        // The stripped x86 files should not appear in modifiedBinaryResources.
        libFiles["x86"]!!.forEach { file ->
            assertFalse(archCoder.modifiedBinaryResources.contains(file),
                "Stripped x86 file should not appear in modifiedBinaryResources")
        }

        // arm64-v8a files should also not appear (they are unchanged).
        libFiles["arm64-v8a"]!!.forEach { file ->
            assertFalse(archCoder.modifiedBinaryResources.contains(file),
                "Unchanged arm64-v8a file should not appear in modifiedBinaryResources")
        }

        assertTrue(archCoder.modifiedResResources.isEmpty(),
            "No res resources should be modified")
    }

    @Test
    fun `modified native library in kept architecture is detected after strip`(@TempDir tempDir: File) {
        val archCoder = createCoderWithKeepArchitectures(
            tempDir, setOf(CpuArchitecture.ARM64_V8A)
        )

        val libFiles = setupNativeLibDirs(listOf("arm64-v8a", "x86"))

        // Capture snapshot.
        archCoder.fileSnapshotCache = archCoder.buildFileSnapshot()

        // Strip.
        archCoder.stripNativeLibraries()

        // Modify a file in the kept architecture.
        val keptFile = libFiles["arm64-v8a"]!![0]
        keptFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05))
        keptFile.setLastModified(System.currentTimeMillis() + 10_000)

        archCoder.detectFileChanges()

        assertTrue(archCoder.modifiedBinaryResources.contains(keptFile),
            "Modified file in kept architecture should be detected")
    }

    @Test
    fun `new native library added to kept architecture after strip is detected`(@TempDir tempDir: File) {
        val archCoder = createCoderWithKeepArchitectures(
            tempDir, setOf(CpuArchitecture.ARM64_V8A)
        )

        setupNativeLibDirs(listOf("arm64-v8a", "x86"))

        // Capture snapshot.
        archCoder.fileSnapshotCache = archCoder.buildFileSnapshot()

        // Strip.
        archCoder.stripNativeLibraries()

        // Add a new file in the kept architecture.
        val newFile = workingDir.resolve("root/lib/arm64-v8a/libnew.so")
        newFile.writeBytes(byteArrayOf(0xDE.toByte(), 0xAD.toByte()))

        archCoder.detectFileChanges()

        assertTrue(archCoder.modifiedBinaryResources.contains(newFile),
            "New file in kept architecture should be detected")
    }
}
