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
import java.util.Collections
import java.util.IdentityHashMap
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

                return PatchResult(this, PatchException("The patch '$this' failed previously").withoutStackTrace())
            }

            // Recursively execute all dependency patches.
            dependencies.forEach { dependency ->
                dependency.execute(executedPatches).exception?.let {
                    return PatchResult(
                        this,
                        PatchException(
                            "The patch \"$this\" depends on \"$dependency\", which raised an exception",
                            it,
                        ).withoutStackTrace(),
                    )
                }
            }

            // Execute the patch. Dependencies already executed above, so they are not included in the time.
            return try {
                if (config.validate) {
                    val patchClasses = context.bytecodeContext.patchClasses
                    val indexStartNanos = patchClasses.indexBuildNanos
                    val startNanos = System.nanoTime()
                    execute(context)
                    val indexBuildNanos = patchClasses.indexBuildNanos - indexStartNanos
                    val executeNanos = System.nanoTime() - startNanos - indexBuildNanos
                    if (executeNanos > SLOW_PATCH_NANOS) {
                        logger.info(
                            "Patch took ${TimeUnit.NANOSECONDS.toMillis(executeNanos)} ms to execute: $logName"
                        )
                    }
                } else {
                    execute(context)
                }

                PatchResult(this)
            } catch (exception: Exception) {
                PatchResult(this, exception.toPatchException())
            }.also { executedPatches[this] = it }
        }

        // Prevent decoding the app manifest twice if it is not needed.
        if (config.resourceMode != ResourceMode.NONE) {
            context.resourceContext.decodeResources(config.resourceMode)
            context.resourceContext.finishDecoding()
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
                } catch (exception: Exception) {
                    PatchResult(patch, exception.toPatchException())
                }

            if (result.exception != null || patch in context.executablePatches) {
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

        /**
         * The classes whose execute and finalize methods call the blocks of a patch.
         */
        val PATCH_CLASS_NAMES = setOf(
            Patch::class.java.name,
            BytecodePatch::class.java.name,
            RawResourcePatch::class.java.name,
            ResourcePatch::class.java.name,
        )

        /**
         * Trim the stack trace of an exception raised by a patch, and wrap it in a [PatchException]
         * if it is not one already.
         */
        fun Exception.toPatchException(): PatchException {
            trimToPatch()

            // The frames of the wrapper are only of the patcher and its caller.
            return this as? PatchException ?: PatchException(this).withoutStackTrace()
        }

        fun PatchException.withoutStackTrace() = apply { stackTrace = emptyArray() }

        /**
         * Remove the frames below the call into the patch from the stack traces of this exception,
         * its causes and its suppressed exceptions. The frames below the patch are only of the
         * patcher and whatever called it, which users do not need to see.
         * A stack trace without a call into a patch, such as one of another thread, is kept.
         */
        fun Throwable.trimToPatch(visited: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())) {
            if (!visited.add(this)) return

            val trace = stackTrace
            // The last frame is the call by the patcher. Earlier ones may be of a patch executing another.
            val patchCallIndex = trace.indexOfLast { frame ->
                frame.className in PATCH_CLASS_NAMES &&
                    (frame.methodName.startsWith("execute") || frame.methodName.startsWith("finalize"))
            }
            if (patchCallIndex >= 0) stackTrace = trace.copyOfRange(0, patchCallIndex + 1)

            cause?.trimToPatch(visited)
            suppressed.forEach { it.trimToPatch(visited) }
        }
    }
}
