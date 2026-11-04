/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

@file:Suppress("DEPRECATION")

package app.morphe.patcher.patch

import java.io.File
import java.util.function.Supplier

/**
 * A common interface for contexts such as [ResourcePatchContext] and [BytecodePatchContext].
 */
@Deprecated("Patches do not need a context. Use the global functions, such as classDefBy() and document().")
sealed interface PatchContext<T> : Supplier<T> {
    /**
     * The path to the file workspace directory.
     * This directory has write access and can be used for temporary files.
     */
    @Deprecated(
        "Use the global property.",
        ReplaceWith("fileWorkspace", "app.morphe.patcher.patch.fileWorkspace"),
    )
    val fileWorkspace: File

    /**
     * If extra validation checks are done, such as when patching with the `--validate` CLI argument.
     * Patches can use this to run their own optional checks that are too slow or too strict
     * to always run for the end user.
     *
     * @see app.morphe.patcher.PatcherConfig.validate
     */
    @Deprecated(
        "Use the global property.",
        ReplaceWith("validate", "app.morphe.patcher.patch.validate"),
    )
    val validate: Boolean
}
