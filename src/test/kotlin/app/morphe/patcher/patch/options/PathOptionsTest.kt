/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch.options

import app.morphe.patcher.patch.ColorOption
import app.morphe.patcher.patch.FilePathOption
import app.morphe.patcher.patch.FilesOption
import app.morphe.patcher.patch.FolderOption
import app.morphe.patcher.patch.ImageOption
import app.morphe.patcher.patch.ImageSize
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.StringOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.colorOption
import app.morphe.patcher.patch.filePathOption
import app.morphe.patcher.patch.filesOption
import app.morphe.patcher.patch.folderOption
import app.morphe.patcher.patch.imageOption
import app.morphe.patcher.patch.stringOption
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object PathOptionsTest {
    private val pathPatch = bytecodePatch(default = true) {
        folderOption("outputFolder", default = "/sdcard/Morphe", description = "Output folder")
        filePathOption(
            "customApk",
            description = "Custom APK",
            allowedExtensions = listOf("apk"),
        )
        filesOption(
            "extraDex",
            default = listOf("/a.dex"),
            allowedExtensions = listOf("dex"),
        )
        imageOption(
            "customIcon",
            description = "Custom icon",
            recommendedSize = ImageSize(512, 512),
        )
        colorOption(
            "accentColor",
            default = "#FF00AA",
            values = mapOf("Purple" to "#8B5CF6", "Red" to "#EF4444"),
        )

        // Legacy StringOption used for a path - must NOT be recognized as FolderOption.
        stringOption("legacyPath", "/sdcard/old", description = "Path to icon folder")
    }

    @Test
    fun `folderOption is a FolderOption subclass`() {
        val option = pathPatch.options["outputFolder"]
        assertIs<FolderOption>(option)
        assertEquals("/sdcard/Morphe", option.default)
        assertEquals(typeOf<String>(), option.type)
    }

    @Test
    fun `filePathOption carries allowedExtensions`() {
        val option = pathPatch.options["customApk"]
        assertIs<FilePathOption>(option)
        assertEquals(listOf("apk"), option.allowedExtensions)
    }

    @Test
    fun `filesOption stores List of String and default`() {
        val option = pathPatch.options["extraDex"]
        assertIs<FilesOption>(option)
        assertEquals(listOf("/a.dex"), option.default)
        assertEquals(listOf("dex"), option.allowedExtensions)
        assertEquals(typeOf<List<String>>(), option.type)
    }

    @Test
    fun `imageOption carries recommendedSize and default allowedExtensions`() {
        val option = pathPatch.options["customIcon"]
        assertIs<ImageOption>(option)
        assertEquals(ImageSize(512, 512), option.recommendedSize)
        assertNotNull(option.allowedExtensions)
        assertTrue("png" in option.allowedExtensions)
    }

    @Test
    fun `colorOption stores presets via values map`() {
        val option = pathPatch.options["accentColor"]
        assertIs<ColorOption>(option)
        assertEquals("#FF00AA", option.default)
        assertEquals("#8B5CF6", option.values?.get("Purple"))
    }

    @Test
    fun `legacy stringOption is NOT auto-promoted to a typed subclass`() {
        val option = pathPatch.options["legacyPath"]
        assertTrue(option !is FolderOption, "stringOption must not be promoted to FolderOption")
        assertTrue(option !is FilePathOption, "stringOption must not be promoted to FilePathOption")
        assertTrue(option !is ImageOption, "stringOption must not be promoted to ImageOption")
        assertTrue(option !is ColorOption, "stringOption must not be promoted to ColorOption")
        assertIs<Option<*>>(option)
        assertEquals(typeOf<String>(), option.type)
    }

    @Test
    fun `stringOption returns a StringOption instance`() {
        assertIs<StringOption>(pathPatch.options["legacyPath"])
        assertIs<StringOption>(stringOption("standalone"))
    }

    @Test
    fun `typed options are also StringOptions (backward compat with old consumers)`() {
        assertIs<StringOption>(pathPatch.options["outputFolder"])   // FolderOption
        assertIs<StringOption>(pathPatch.options["customApk"])      // FilePathOption
        assertIs<StringOption>(pathPatch.options["customIcon"])     // ImageOption
        assertIs<StringOption>(pathPatch.options["accentColor"])    // ColorOption
        // FilesOption stores List<String>, so it's NOT a StringOption
        assertTrue(pathPatch.options["extraDex"] !is StringOption)
    }

    @Test
    fun `StringOption exposes file helper`() {
        val opt = pathPatch.options["outputFolder"] as FolderOption
        opt.reset()   // isolate from other tests mutating the shared patch
        assertNotNull(opt.file)
        assertEquals("/sdcard/Morphe", opt.file!!.path.replace('\\', '/'))
        assertNotNull(opt.directory)                 // alias
        assertEquals(opt.file, opt.directory)
        // Blank / null → null
        pathPatch.options["outputFolder"] = null
        assertNull((pathPatch.options["outputFolder"] as FolderOption).file)
    }

    @Test
    fun `FilesOption exposes files helper`() {
        val opt = pathPatch.options["extraDex"] as FilesOption
        val files = opt.files
        assertNotNull(files)
        assertEquals(1, files.size)
        assertEquals("/a.dex", files[0].path.replace('\\', '/'))
    }

    @Test
    fun `ColorOption parses hex to ARGB int`() {
        val opt = pathPatch.options["accentColor"] as ColorOption
        opt.reset()   // isolate from other tests mutating the shared patch
        // Default "#FF00AA" (RGB) → alpha assumed FF → 0xFFFF00AA (which as signed Int is negative)
        assertEquals(0xFFFF00AA.toInt(), opt.colorInt)

        pathPatch.options["accentColor"] = "#80112233"      // AARRGGBB
        assertEquals(0x80112233.toInt(), opt.colorInt)

        pathPatch.options["accentColor"] = "@android:color/black" // resource ref
        assertNull(opt.colorInt)

        pathPatch.options["accentColor"] = null
        assertNull(opt.colorInt)
    }

    @Test
    fun `Map-based setOptions API still works for a ColorOption`() {
        val option = pathPatch.options["accentColor"]
        // Setting via the polymorphic Options API stores the raw String value.
        pathPatch.options["accentColor"] = "#000000"
        assertEquals("#000000", option.value)
    }

    @Test
    fun `standalone builders return the correct subclass`() {
        assertIs<FolderOption>(folderOption("x"))
        assertIs<FilePathOption>(filePathOption("x"))
        assertIs<FilesOption>(filesOption("x"))
        assertIs<ImageOption>(imageOption("x"))
        assertIs<ColorOption>(colorOption("x"))
    }

    @Test
    fun `option value defaults are honoured on typed subclasses`() {
        val opt = pathPatch.options["outputFolder"] as FolderOption
        assertEquals("/sdcard/Morphe", opt.value)
        opt.reset()
        assertEquals("/sdcard/Morphe", opt.value)
        pathPatch.options["outputFolder"] = null
        assertNull(opt.value)
    }
}
