/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.resource.ResourceMode
import brut.androlib.Config
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
 */
class PatcherConfig(
    internal val apkFile: File,
    private val temporaryFilesPath: File = File("morphe-temporary-files"),
    private val aaptBinaryPath: String? = null,
    private val frameworkFileDirectory: String? = null,
    internal val useArsclib: Boolean = true
) {
    private val logger = Logger.getLogger(PatcherConfig::class.java.name)

    /**
     * The mode to use for resource decoding and compiling.
     *
     * @see ResourceMode
     */
    internal var resourceMode = ResourceMode.NONE

    /**
     * The path to the temporary apk files directory.
     */
    internal val apkFiles = temporaryFilesPath.resolve("apk")

    /**
     * The configuration for decoding and compiling resources.
     */
    internal val resourceConfig: Config = Config().apply {
        aaptVersion = 2
        aaptBinaryPath?.let { setAaptBinaryPath(it) }
        frameworkFileDirectory?.let { frameworkDirectory = it }
    }

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
