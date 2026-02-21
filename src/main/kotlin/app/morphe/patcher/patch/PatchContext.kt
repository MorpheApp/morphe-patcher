/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.patch

import java.util.function.Supplier

/**
 * A common interface for contexts such as [ResourcePatchContext] and [BytecodePatchContext].
 */

sealed interface PatchContext<T> : Supplier<T>
