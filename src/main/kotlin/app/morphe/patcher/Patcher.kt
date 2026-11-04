/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

@file:Suppress("DEPRECATION")

package app.morphe.patcher

import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.apk.CompressedDexFiles
import app.morphe.patcher.apk.dexFilesStoredIn
import app.morphe.patcher.apk.overlapDexCompression
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.logging.PhaseTimings
import app.morphe.patcher.logging.toLogString
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.PatchResult
import app.morphe.patcher.patch.RawResourcePatch
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.anyRecursively
import app.morphe.patcher.patch.decodesOnFirstUse
import app.morphe.patcher.resource.ResourceMode
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.time.Duration.Companion.nanoseconds

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
    operator fun plusAssign(patches: Set<Patch>) {
        // Add all patches to the executablePatches set.
        context.executablePatches += patches

        // Add all patches and their dependencies to the allPatches set.
        patches.forEach { patch ->
            fun Patch.addRecursively() =
                also(context.allPatches::add).dependencies.forEach(Patch::addRecursively)

            patch.addRecursively()
        }

        context.allPatches.let { allPatches ->
            // The deprecated patch types declare what they use, which is decoded before patches execute.
            // What other patches use is decoded when they first need it, and these can need decoded
            // resources even if the resources of a raw resource patch were extracted.
            val anyRawResourcePatch = allPatches.any { patch -> patch.anyRecursively { it is RawResourcePatch } }
            val anyDecodesOnFirstUse = allPatches.any { patch -> patch.anyRecursively { it.decodesOnFirstUse } }

            // Check, if what kind of resource mode is required.
            config.resourceMode = if (
                allPatches.any { patch -> patch.anyRecursively { it is ResourcePatch } } ||
                (anyRawResourcePatch && anyDecodesOnFirstUse)
            ) {
                ResourceMode.FULL
            } else if (anyRawResourcePatch) {
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
     * @param onPatchExecuting Called on the thread that runs a patch, right before it executes or
     *                         finalizes, dependencies included. The flow reports a patch only once
     *                         it is done, which leaves a caller blind to one that never is.
     * @return A flow of [PatchResult]s.
     */
    operator fun invoke(onPatchExecuting: (Patch) -> Unit = {}) = flow {
        fun Patch.execute(
            executedPatches: LinkedHashMap<Patch, PatchResult>,
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
            onPatchExecuting(this)
            return try {
                if (config.validate) {
                    fun preparationNanos() = context.bytecodeContext.preparationNanos + context.resourceContext.decodeNanos

                    val preparationStartNanos = preparationNanos()
                    val startNanos = System.nanoTime()
                    execute(context)
                    val executeNanos = System.nanoTime() - startNanos - (preparationNanos() - preparationStartNanos)
                    if (executeNanos > SLOW_PATCH_NANOS) {
                        logger.info(
                            "Patch took ${executeNanos.nanoseconds.toLogString()} to execute: $this"
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

        // Resources can be decoded after the DEX files, so this cannot be left to decoding them.
        config.initializeTemporaryFilesDirectories()

        // Prevent decoding the app manifest twice if it is not needed.
        if (config.resourceMode != ResourceMode.NONE) {
            context.resourceContext.decodeResources(config.resourceMode)
            context.resourceContext.finishDecoding()
        } else {
            context.resourceContext.decodeOnFirstUse = true
        }

        if (config.bytecodeMode != BytecodeMode.NONE) {
            context.bytecodeContext.decodeDexFiles()
        } else {
            context.bytecodeContext.decodeOnFirstUse = true
        }

        logger.info("Executing patches")

        val executedPatches = LinkedHashMap<Patch, PatchResult>()

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

            onPatchExecuting(patch)
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
        val (dexFiles, dexFilesComplete) = compileDexFiles()
        val resFiles = context.resourceContext.get()
        // The output base of applyTo may lack the DEX entries it replaces, so the input tells how to store them.
        val storedDexFileNames = dexFilesStoredIn(config.apkFile, dexFiles.map { it.name })
        return PatcherResult(dexFiles, resFiles, dexFilesComplete, storedDexFileNames)
    }

    /**
     * Compiles the patched files and writes the patched APK in one pass, instead of [get] followed by
     * [ApkUtils.applyTo] on a copy of the input APK.
     *
     * The APK holds what [ApkUtils.applyTo] would write and is aligned the same way, but it is not signed,
     * so sign it with [ApkUtils.signApk] before it is installed, which can sign it in place. The DEX files
     * are compressed while the resources are encoded. A DEX file the input APK stores uncompressed, as an
     * app that loads its code from the APK must, is stored uncompressed, as [ApkUtils.applyTo] stores it.
     *
     * Like [get], this ends patching: the patcher cannot be used afterward, other than to be closed.
     *
     * @param outputApk The file to write the patched APK to, replacing it if it exists.
     * @throws IllegalArgumentException If [outputApk] is the input APK, which is read while it is written.
     */
    fun writeApk(outputApk: File) {
        require(outputApk.canonicalFile != config.apkFile.canonicalFile) {
            "The patched APK cannot be written over the input APK: $outputApk"
        }

        val timings = PhaseTimings(logger, "writeApk")
        val (dexFiles, _) = compileDexFiles()
        config.patchedFiles.mkdirs()
        CompressedDexFiles(
            dexFiles,
            config.patchedFiles.resolve("dex.zip"),
            config.apkFile,
            timings,
            overlapDexCompression,
        ).use { compressedDexFiles ->
            context.resourceContext.writeApk(outputApk, compressedDexFiles, timings)
        }
        timings.log()
    }

    /**
     * Ends patching and writes the patched DEX files, which is how [get] and [writeApk] begin.
     *
     * @return The patched DEX files, and whether they replace every DEX file of the input APK.
     */
    @OptIn(InternalApi::class)
    private fun compileDexFiles(): Pair<Set<PatcherResult.PatchedDexFile>, Boolean> {
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
        return dexFiles to dexFilesComplete
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
