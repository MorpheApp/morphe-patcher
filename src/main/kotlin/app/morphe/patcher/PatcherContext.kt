/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

@file:Suppress("DEPRECATION")

package app.morphe.patcher

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.ResourcePatchContext
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
    internal val executablePatches = mutableSetOf<Patch>()

    /**
     * The set of all [Patch]es and their dependencies.
     */
    internal val allPatches = mutableSetOf<Patch>()

    /**
     * The context for patches containing the current state of the resources.
     */
    internal val resourceContext = ResourcePatchContext(config)

    /**
     * The path to the file workspace directory.
     */
    val fileWorkspace = config.fileWorkspace

    /**
     * [PackageMetadata] of the supplied [PatcherConfig.apkFile].
     */
    val packageMetadata = resourceContext.packageMetadata

    /**
     * The context for patches containing the current state of the bytecode.
     */
    internal val bytecodeContext = BytecodePatchContext(config, packageMetadata)

    init {
        current = this
    }

    override fun close() {
        bytecodeContext.close()
        resourceContext.close()
        if (current === this) current = null
    }

    internal companion object {
        /**
         * The context of the APK being patched, for the global functions of patches such as
         * [app.morphe.patcher.patch.classDefBy]. Only one APK is ever patched at a time.
         */
        @Volatile
        internal var current: PatcherContext? = null
    }
}
