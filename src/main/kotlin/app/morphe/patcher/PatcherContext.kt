/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.resource.ResourceMode
import com.reandroid.apk.ApkModule
import java.io.Closeable

/**
 * A context for the patcher containing the current state of the patcher.
 *
 * @param config The configuration for the patcher.
 */
@Suppress("MemberVisibilityCanBePrivate")
class PatcherContext internal constructor(config: PatcherConfig): Closeable {
    /**
     * The set of [Patch]es.
     */
    internal val executablePatches = mutableSetOf<Patch<*>>()

    /**
     * The set of all [Patch]es and their dependencies.
     */
    internal val allPatches = mutableSetOf<Patch<*>>()

    /**
     * The context for patches containing the current state of the resources.
     */
    internal val resourceContext = ResourcePatchContext(config)

    /**
     * [PackageMetadata] of the supplied [PatcherConfig.apkFile].
     */
    val packageMetadata = resourceContext.packageMetadata

    /**
     * The context for patches containing the current state of the bytecode.
     */
    internal val bytecodeContext = BytecodePatchContext(config, packageMetadata)

    override fun close() = bytecodeContext.close()
}
