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
 * @param fileWorkspacePath Temporary file workspace that patches may use.
 * @param targetPatcherVersion The version of Morphe Patcher whose behavior patching uses, such as `2.0.0`.
 * Behavior that changed in a way older patches may not expect only applies to patches built for the version
 * that changed it or a newer one. Null, the default, uses the newest version the patch bundles of the
 * added patches were built for, as the `Patcher-Version` attribute of their manifests declares it.
 * Without any, patching uses the behavior of the oldest version.
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
    private val fileWorkspacePath: File? = null,
    internal val targetPatcherVersion: String? = null,
) {
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor(
        apkFile: File,
        temporaryFilesPath: File,
        aaptBinaryPath: String?,
        frameworkFileDirectory: String?,
        useArsclib: Boolean,
        keepArchitectures: Set<CpuArchitecture>,
        useBytecodeMode: BytecodeMode,
        verifier: DexVerifier,
        fileWorkspacePath: File?,
    ) : this(
        apkFile,
        temporaryFilesPath,
        aaptBinaryPath,
        frameworkFileDirectory,
        useArsclib,
        keepArchitectures,
        useBytecodeMode,
        verifier,
        fileWorkspacePath,
        null,
    )

    private val logger = Logger.getLogger(PatcherConfig::class.java.name)

    /**
     * The path to the file workspace directory.
     * This directory has write access and can be used for temporary files.
     */
    val fileWorkspace: File = fileWorkspacePath ?: temporaryFilesPath.resolve("workspace")

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
     * The version of Morphe Patcher whose behavior patching uses, or null for the behavior of the oldest
     * version. Resolved when patches are added, see [targetPatcherVersion].
     */
    internal var behaviorVersion: PatcherVersion? = null

    /**
     * Whether [behavior] applies to the patches being applied.
     */
    internal fun uses(behavior: PatcherBehavior) = behavior.appliesTo(behaviorVersion)

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
