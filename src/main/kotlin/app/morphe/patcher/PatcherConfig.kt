/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.dex.DexVerifier
import app.morphe.patcher.dex.NoOpDexVerifier
import app.morphe.patcher.resource.CpuArchitecture
import app.morphe.patcher.resource.ResourceMode
import java.io.File
import java.util.logging.Logger

/**
 * The configuration for the patcher.
 *
 * @param apkFile The apk file to patch.
 * @param temporaryFilesPath A path to a folder to store temporary files in.
 * @param aaptBinaryPath A path to a custom aapt binary.
 * @param frameworkFileDirectory A path to the directory to cache the framework file in.
 * @param useArsclib Whether to use Arsclib for resource decoding and compiling.
 * @param verifier The output verifier to use.
 */
class PatcherConfig(
    internal val apkFile: File,
    private val temporaryFilesPath: File = File("morphe-temporary-files"),
    @Deprecated("apktool support is deprecated") private val aaptBinaryPath: String? = null,
    @Deprecated("apktool support is deprecated") private val frameworkFileDirectory: String? = null,
    @Deprecated("apktool support is deprecated") internal val useArsclib: Boolean = true,
    internal val keepArchitectures: Set<CpuArchitecture> = emptySet(),
    internal val useBytecodeMode: BytecodeMode = BytecodeMode.STRIP_FAST,
    internal val verifier: DexVerifier = NoOpDexVerifier,
) {
    private val logger = Logger.getLogger(PatcherConfig::class.java.name)

    init {
        if (!useArsclib) {
            logger.warning { "apktool support is deprecated. arsclib will be used." }
        }
    }

    /**
     * The mode to use for resource decoding and compiling.
     *
     * @see ResourceMode
     */
    internal var resourceMode = ResourceMode.NONE

    /**
     * The mode to use for resource decoding and compiling.
     *
     * @see BytecodeMode
     */
    internal var bytecodeMode = BytecodeMode.NONE

    /**
     * The path to the temporary apk files directory.
     */
    internal val apkFiles = temporaryFilesPath.resolve("apk")

    /**
     * The path to the temporary patched files directory.
     */
    internal val patchedFiles = temporaryFilesPath.resolve("patched")

    /**
     * Initialize the temporary files' directories.
     * This will delete the existing temporary files directory if it exists.
     */
    internal fun initializeTemporaryFilesDirectories() {
        temporaryFilesPath.apply {
            if (exists()) {
                logger.info("Deleting existing temporary files directory")

                if (!deleteRecursively()) {
                    logger.severe("Failed to delete existing temporary files directory")
                }
            }
        }

        apkFiles.mkdirs()
        patchedFiles.mkdirs()
    }
}
