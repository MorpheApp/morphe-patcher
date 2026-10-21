/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.PatchResult
import app.morphe.patcher.patch.RawResourcePatch
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.anyRecursively
import app.morphe.patcher.resource.ResourceMode
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * A Patcher.
 *
 * @param config The configuration to use for the patcher.
 */
class Patcher(private val config: PatcherConfig) : Closeable {
    private val logger = Logger.getLogger(this::class.java.name)

    /**
     * The context containing the current state of the patcher.
     */
    val context = PatcherContext(config)

    /**
     * Add patches.
     *
     * @param patches The patches to add.
     */
    operator fun plusAssign(patches: Set<Patch<*>>) {
        // Add all patches to the executablePatches set.
        context.executablePatches += patches

        // Add all patches and their dependencies to the allPatches set.
        patches.forEach { patch ->
            fun Patch<*>.addRecursively() =
                also(context.allPatches::add).dependencies.forEach(Patch<*>::addRecursively)

            patch.addRecursively()
        }

        context.allPatches.let { allPatches ->
            // Check, if what kind of resource mode is required.
            config.resourceMode = if (allPatches.any { patch -> patch.anyRecursively { it is ResourcePatch } }) {
                ResourceMode.FULL
            } else if (allPatches.any { patch -> patch.anyRecursively { it is RawResourcePatch } }) {
                ResourceMode.RAW_ONLY
            } else {
                ResourceMode.NONE
            }

            config.bytecodeMode = if (allPatches.any { patch -> patch.anyRecursively { it is BytecodePatch } }) {
                config.useBytecodeMode
            } else {
                BytecodeMode.NONE
            }

            config.behaviors = resolvePatcherBehaviors(config.targetPatcherVersion, allPatches, logger)
            Fingerprint.rawStringComparison = if (config.uses(PatcherBehavior.EXACT_FINGERPRINT_STRINGS)) {
                StringComparisonType.EQUALS
            } else {
                StringComparisonType.CONTAINS
            }
            Fingerprint.retryAllFilters = config.uses(PatcherBehavior.RETRY_ALL_INSTRUCTION_FILTERS)
        }
    }

    /**
     * Execute added patches.
     *
     * @return A flow of [PatchResult]s.
     */
    operator fun invoke() = flow {
        fun Patch<*>.execute(
            executedPatches: LinkedHashMap<Patch<*>, PatchResult>,
        ): PatchResult {
            // If the patch was executed before or failed, return it's the result.
            executedPatches[this]?.let { patchResult ->
                patchResult.exception ?: return patchResult

                return PatchResult(this, PatchException("The patch '$this' failed previously"))
            }

            // Recursively execute all dependency patches.
            dependencies.forEach { dependency ->
                dependency.execute(executedPatches).exception?.let {
                    return PatchResult(
                        this,
                        PatchException(
                            "The patch \"$this\" depends on \"$dependency\", which raised an exception:\n${it.stackTraceToString()}",
                        ),
                    )
                }
            }

            // Execute the patch. Dependencies already executed above, so they are not included in the time.
            return try {
                if (config.validate) {
                    val startNanos = System.nanoTime()
                    execute(context)
                    val executeNanos = System.nanoTime() - startNanos
                    if (executeNanos > SLOW_PATCH_NANOS) {
                        logger.info(
                            "Patch took ${TimeUnit.NANOSECONDS.toMillis(executeNanos)} ms to execute: $logName"
                        )
                    }
                } else {
                    execute(context)
                }

                PatchResult(this)
            } catch (exception: PatchException) {
                PatchResult(this, exception)
            } catch (exception: Exception) {
                PatchResult(this, PatchException(exception))
            }.also { executedPatches[this] = it }
        }

        // Prevent decoding the app manifest twice if it is not needed.
        if (config.resourceMode != ResourceMode.NONE) {
            context.resourceContext.decodeResources(config.resourceMode)
        }

        if (config.bytecodeMode != BytecodeMode.NONE) {
            context.bytecodeContext.decodeDexFiles()
        }

        logger.info("Executing patches")

        val executedPatches = LinkedHashMap<Patch<*>, PatchResult>()

        context.executablePatches.sortedBy { it.name }.forEach { patch ->
            val patchResult = patch.execute(executedPatches)

            // If an exception occurred or the patch has no finalize block, emit the result.
            if (patchResult.exception != null || patch.finalizeBlock == null) {
                emit(patchResult)
            }
        }

        val succeededPatchesWithFinalizeBlock = executedPatches.values.filter {
            it.exception == null && it.patch.finalizeBlock != null
        }

        succeededPatchesWithFinalizeBlock.asReversed().forEach { executionResult ->
            val patch = executionResult.patch

            val result =
                try {
                    patch.finalize(context)

                    executionResult
                } catch (exception: PatchException) {
                    PatchResult(patch, exception)
                } catch (exception: Exception) {
                    PatchResult(patch, PatchException(exception))
                }

            if (result.exception != null) {
                emit(
                    PatchResult(
                        patch,
                        PatchException(
                            "The patch \"$patch\" raised an exception: ${result.exception.stackTraceToString()}",
                            result.exception,
                        ),
                    ),
                )
            } else if (patch in context.executablePatches) {
                emit(result)
            }
        }
    }

    override fun close() {
        // Do not leave the matching of patches built for an older version to code outside a patcher.
        Fingerprint.rawStringComparison = StringComparisonType.EQUALS
        Fingerprint.retryAllFilters = true
        context.close()
    }

    /**
     * Compile and save patched APK files.
     *
     * @return The [PatcherResult] containing the patched APK files.
     */
    @OptIn(InternalApi::class)
    fun get(): PatcherResult {
        Fingerprint.clearFingerprints()
        context.allPatches.clear()
        context.executablePatches.clear()
        // Patches are done requesting resources, so the resource table need not be held while the DEX
        // files are written.
        context.resourceContext.finishDecoding()
        val dexFiles = context.bytecodeContext.get()
        context.bytecodeContext.close()
        val dexFilesComplete = config.bytecodeMode != BytecodeMode.STRIP_FAST || context.bytecodeContext.dexFilesComplete
        val dexFileNames = dexFiles.mapTo(HashSet()) { it.name }
        context.resourceContext.omitEntry = { name -> ApkUtils.isReplacedOnApply(name, dexFileNames, dexFilesComplete) }
        val resFiles = context.resourceContext.get()
        return PatcherResult(dexFiles, resFiles, dexFilesComplete)
    }

    private companion object {
        /**
         * Executing a patch that takes longer than this is logged if [PatcherConfig.validate] is set.
         */
        val SLOW_PATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(100)
    }
}
