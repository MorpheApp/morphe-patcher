/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

@file:Suppress("unused", "DEPRECATION")

package app.morphe.patcher.patch

import java.io.File
import kotlin.reflect.typeOf

/**
 * Recommended pixel dimensions for an [ImageOption].
 *
 * @param width  Recommended width in pixels.
 * @param height Recommended height in pixels.
 */
data class ImageSize(val width: Int, val height: Int)

/**
 * Base class for string-valued [Option]s. Subclasses ([FolderOption], [FilePathOption],
 * [ImageOption], [ColorOption]) add semantic hints (picker kind, MIME filters,
 * presets) and typed accessors while preserving [String] as the underlying value.
 * Consumers that treat the option as `Option<String>` see and mutate the same
 * plain string as with a raw [stringOption].
 */
open class StringOption @PublishedApi internal constructor(
    key: String,
    default: String? = null,
    values: Map<String, String?>? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<String>.(String?) -> Boolean = { true },
) : Option<String>(
    key,
    default,
    values,
    title,
    description,
    required,
    typeOf<String>(),
    validator,
) {
    /**
     * The current [value] as a [File], or `null` when unset or blank.
     * The path is not stat'ed. Callers should still check [File.exists] etc.
     */
    val file: File? get() = value?.takeIf { it.isNotBlank() }?.let(::File)
}

/**
 * A folder-path option. Signals to editors that this option should be edited
 * via a folder picker rather than a text field.
 */
class FolderOption @PublishedApi internal constructor(
    key: String,
    default: String? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<String>.(String?) -> Boolean = { true },
) : StringOption(key, default, null, title, description, required, validator) {
    /** Alias for [file]. */
    val directory: File? get() = file
}

/**
 * A single-file-path option. Signals to editors that this option should be
 * edited via a file picker.
 *
 * @property allowedExtensions Optional file extensions the picker should filter by
 *   (e.g. `listOf("apk", "zip")`). `null` means any file.
 */
class FilePathOption @PublishedApi internal constructor(
    key: String,
    default: String? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    val allowedExtensions: List<String>? = null,
    validator: Option<String>.(String?) -> Boolean = { true },
) : StringOption(key, default, null, title, description, required, validator)

/**
 * A multi-file-path option. Signals to editors that this option should be edited
 * via a file picker that allows selecting multiple files.
 *
 * The stored value is a list of file paths.
 *
 * @property allowedExtensions Optional file extensions the picker should filter by.
 */
class FilesOption @PublishedApi internal constructor(
    key: String,
    default: List<String>? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    val allowedExtensions: List<String>? = null,
    validator: Option<List<String>>.(List<String>?) -> Boolean = { true },
) : Option<List<String>>(
    key,
    default,
    null,
    title,
    description,
    required,
    typeOf<List<String>>(),
    validator,
) {
    /** The current list of paths as [File] instances, or `null` when unset. */
    val files: List<File>? get() = value?.map(::File)
}

/**
 * An image-file option. Signals to editors that this option should be edited via
 * a file picker restricted to image files. The stored value is a file path.
 *
 * @property allowedExtensions File extensions the picker should filter by. Defaults
 *   to common image formats (`png`, `jpg`, `jpeg`, `webp`).
 * @property recommendedSize Optional recommended pixel dimensions the editor may
 *   show as a hint to the user.
 */
class ImageOption @PublishedApi internal constructor(
    key: String,
    default: String? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    val allowedExtensions: List<String>? = listOf("png", "jpg", "jpeg", "webp"),
    val recommendedSize: ImageSize? = null,
    validator: Option<String>.(String?) -> Boolean = { true },
) : StringOption(key, default, null, title, description, required, validator)

/**
 * A color option. Signals to editors that this option should be edited via a
 * color picker. The stored value is a hex color string, e.g. `"#FF00AA"`.
 *
 * Presets may be provided via [values] mapping human-readable names to hex strings.
 */
class ColorOption @PublishedApi internal constructor(
    key: String,
    default: String? = null,
    values: Map<String, String?>? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<String>.(String?) -> Boolean = { true },
) : StringOption(key, default, values, title, description, required, validator) {
    /**
     * The current hex color parsed to an ARGB int, or `null` when unset, blank,
     * malformed, or an Android resource reference (e.g. "@android:color/black")
     * that requires a resource context to resolve.
     *
     * Accepts `#RRGGBB` (alpha assumed opaque) and `#AARRGGBB`.
     */
    val colorInt: Int? get() {
        val hex = value?.trim()?.removePrefix("#") ?: return null
        return when (hex.length) {
            6 -> "FF$hex".toLongOrNull(16)?.toInt()
            8 -> hex.toLongOrNull(16)?.toInt()
            else -> null
        }
    }
}

// region Builders

/**
 * Create a new [FolderOption].
 */
fun folderOption(
    key: String,
    default: String? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<String>.(String?) -> Boolean = { true },
) = FolderOption(key, default, title, description, required, validator)

/**
 * Create a new [FolderOption] and add it to the current [PatchBuilder].
 */
fun PatchBuilder<*>.folderOption(
    key: String,
    default: String? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<String>.(String?) -> Boolean = { true },
): FolderOption = app.morphe.patcher.patch.folderOption(
    key, default, title, description, required, validator,
).also { it() }

/**
 * Create a new [FilePathOption].
 */
fun filePathOption(
    key: String,
    default: String? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    allowedExtensions: List<String>? = null,
    validator: Option<String>.(String?) -> Boolean = { true },
) = FilePathOption(key, default, title, description, required, allowedExtensions, validator)

/**
 * Create a new [FilePathOption] and add it to the current [PatchBuilder].
 */
fun PatchBuilder<*>.filePathOption(
    key: String,
    default: String? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    allowedExtensions: List<String>? = null,
    validator: Option<String>.(String?) -> Boolean = { true },
): FilePathOption = app.morphe.patcher.patch.filePathOption(
    key, default, title, description, required, allowedExtensions, validator,
).also { it() }

/**
 * Create a new [FilesOption].
 */
fun filesOption(
    key: String,
    default: List<String>? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    allowedExtensions: List<String>? = null,
    validator: Option<List<String>>.(List<String>?) -> Boolean = { true },
) = FilesOption(key, default, title, description, required, allowedExtensions, validator)

/**
 * Create a new [FilesOption] and add it to the current [PatchBuilder].
 */
fun PatchBuilder<*>.filesOption(
    key: String,
    default: List<String>? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    allowedExtensions: List<String>? = null,
    validator: Option<List<String>>.(List<String>?) -> Boolean = { true },
): FilesOption = app.morphe.patcher.patch.filesOption(
    key, default, title, description, required, allowedExtensions, validator,
).also { it() }

/**
 * Create a new [ImageOption].
 */
fun imageOption(
    key: String,
    default: String? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    allowedExtensions: List<String>? = listOf("png", "jpg", "jpeg", "webp"),
    recommendedSize: ImageSize? = null,
    validator: Option<String>.(String?) -> Boolean = { true },
) = ImageOption(key, default, title, description, required, allowedExtensions, recommendedSize, validator)

/**
 * Create a new [ImageOption] and add it to the current [PatchBuilder].
 */
fun PatchBuilder<*>.imageOption(
    key: String,
    default: String? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    allowedExtensions: List<String>? = listOf("png", "jpg", "jpeg", "webp"),
    recommendedSize: ImageSize? = null,
    validator: Option<String>.(String?) -> Boolean = { true },
): ImageOption = app.morphe.patcher.patch.imageOption(
    key, default, title, description, required, allowedExtensions, recommendedSize, validator,
).also { it() }

/**
 * Create a new [ColorOption].
 */
fun colorOption(
    key: String,
    default: String? = null,
    values: Map<String, String?>? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<String>.(String?) -> Boolean = { true },
) = ColorOption(key, default, values, title, description, required, validator)

/**
 * Create a new [ColorOption] and add it to the current [PatchBuilder].
 */
fun PatchBuilder<*>.colorOption(
    key: String,
    default: String? = null,
    values: Map<String, String?>? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<String>.(String?) -> Boolean = { true },
): ColorOption = app.morphe.patcher.patch.colorOption(
    key, default, values, title, description, required, validator,
).also { it() }

// endregion
