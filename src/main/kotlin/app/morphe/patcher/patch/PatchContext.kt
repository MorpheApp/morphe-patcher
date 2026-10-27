/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.patch

import java.io.File
import java.util.function.Supplier

/**
 * A common interface for contexts such as [ResourcePatchContext] and [BytecodePatchContext].
 */

sealed interface PatchContext<T> : Supplier<T> {
    /**
     * The path to the file workspace directory.
     * This directory has write access and can be used for temporary files.
     */
    val fileWorkspace: File

    /**
     * If extra validation checks are done, such as when patching with the `--validate` CLI argument.
     * Patches can use this to run their own optional checks that are too slow or too strict
     * to always run for the end user.
     *
     * @see app.morphe.patcher.PatcherConfig.validate
     */
    val validate: Boolean
}
