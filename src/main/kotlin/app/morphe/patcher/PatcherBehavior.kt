/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchBundleInfo
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.anyRecursively
import java.util.logging.Logger

/**
 * Behavior of Morphe Patcher that changed in a way patches built for an older version may not expect.
 *
 * Patches are built for the version their patch bundle declares with the `Patcher-Version` attribute of
 * its manifest. Whether a behavior applies depends on the versions of the patch bundles being applied and
 * on its [adoption], see [resolvePatcherBehaviors].
 *
 * @param since The first version the behavior applies to.
 * @param adoption Which patch bundles must be built for [since] or newer for the behavior to apply.
 * @param description What changed, as the logs about patches built for an older version describe it.
 * @param affectsPatch Whether the behavior can change what a patch does.
 */
internal enum class PatcherBehavior(
    since: String,
    val adoption: Adoption,
    val description: String,
    private val affectsPatch: (Patch<*>) -> Boolean,
) {
    /**
     * Resources are decoded when a patch requests them, instead of all before patches execute.
     */
    ON_REQUEST_RESOURCE_DECODING(
        since = "2.0.0-0", // Includes pre-release.
        // A patch built for an older version may list a directory it expects to be decoded.
        adoption = Adoption.EVERY_BUNDLE,
        description = "Resources are decoded when patches request them, instead of all before patches execute",
        affectsPatch = { patch -> patch.anyRecursively { it is ResourcePatch } },
    );

    val since = PatcherVersion.parse(since) ?: throw IllegalStateException("Invalid version: $since")

    /**
     * @param version The version a patch bundle was built for, or null if it is unknown, which is the
     * oldest version.
     */
    fun appliesTo(version: PatcherVersion?) = version != null && version >= since

    fun affects(patch: Patch<*>) = affectsPatch(patch)

    /**
     * Which patch bundles must be built for [since] or newer for a behavior to apply. Only bundles with
     * patches the behavior [affects] count, and patches that were not loaded from a bundle have no say.
     */
    enum class Adoption {
        /**
         * The behavior applies to the patches of every bundle once one of them is built for [since] or
         * newer. Bundles built for an older version are named in a warning.
         */
        NEWEST_BUNDLE,

        /**
         * The behavior applies only if every bundle is built for [since] or newer, so a bundle built for
         * an older version keeps the old behavior for the patches of every bundle.
         */
        EVERY_BUNDLE,
    }
}

/**
 * The behaviors of Morphe Patcher that apply to [patches].
 *
 * If a host [configured] a version, the behaviors of that version apply. Otherwise, each behavior applies
 * as its [PatcherBehavior.adoption] says, by the versions the patch bundles of [patches] were built for.
 *
 * Patches of a bundle built for an older version may not work as intended with a behavior of a newer one,
 * so each such bundle whose patches an applied behavior affects is logged as a warning.
 *
 * @param adoption The adoption of a behavior, which tests replace.
 */
internal fun resolvePatcherBehaviors(
    configured: String?,
    patches: Collection<Patch<*>>,
    logger: Logger,
    adoption: (PatcherBehavior) -> PatcherBehavior.Adoption = PatcherBehavior::adoption,
): Set<PatcherBehavior> {
    val patchesByBundle = patches.filter { it.bundle != null }.groupBy { it.bundle!! }
    val bundleVersions = patchesByBundle.keys.associateWith { PatcherVersion.parse(it.patcherVersion) }

    fun PatcherBehavior.outdatedBundles() = patchesByBundle.filter { (bundle, patches) ->
        !appliesTo(bundleVersions[bundle]) && patches.any(::affects)
    }.keys

    val target: PatcherVersion?
    val behaviors: Set<PatcherBehavior>

    if (configured != null) {
        target = PatcherVersion.parse(configured).also {
            if (it == null) logger.warning("Ignoring the invalid target Morphe Patcher version \"$configured\"")
        }
        behaviors = PatcherBehavior.entries.filterTo(mutableSetOf()) { it.appliesTo(target) }
    } else {
        target = bundleVersions.values.filterNotNull().maxOrNull()
        behaviors = PatcherBehavior.entries.filterTo(mutableSetOf()) { behavior ->
            if (!behavior.appliesTo(target)) return@filterTo false
            when (adoption(behavior)) {
                PatcherBehavior.Adoption.NEWEST_BUNDLE -> true
                PatcherBehavior.Adoption.EVERY_BUNDLE -> behavior.outdatedBundles().isEmpty()
            }
        }
    }

    val outdated = patchesByBundle.keys.associateWith { bundle ->
        behaviors.filter { bundle in it.outdatedBundles() }
    }.filterValues { it.isNotEmpty() }
    if (outdated.isNotEmpty()) {
        warnAboutOutdatedBundles(target, outdated, bundleVersions.filterValues { it == target }.keys, logger)
    }

    return behaviors
}

private fun warnAboutOutdatedBundles(
    target: PatcherVersion?,
    outdated: Map<PatchBundleInfo, List<PatcherBehavior>>,
    newest: Set<PatchBundleInfo>,
    logger: Logger,
) {
    logger.warning(
        buildString {
            append("Patches built for an older version of Morphe Patcher are applied with the behavior of ")
            append("Morphe Patcher $target")
            if (newest.isNotEmpty()) append(" that ${newest.joinToString()} was built for")
            appendLine(", and may not work as intended:")

            outdated.forEach { (bundle, changed) ->
                appendLine("  $bundle, built for Morphe Patcher ${bundle.patcherVersion ?: "of an unknown version"}")
                changed.forEach { appendLine("    - ${it.description}") }
            }

            append("Use the patch bundles of the same Morphe Patcher version, or update the outdated ones.")
        },
    )
}
