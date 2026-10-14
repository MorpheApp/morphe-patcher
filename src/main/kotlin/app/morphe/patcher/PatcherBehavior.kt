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
 * its manifest. A behavior applies when patching uses the behavior of [since] or a newer version, see
 * [targetPatcherVersion], so patches built for an older version keep working the way they were written
 * when they are applied on their own.
 *
 * @param since The first version the behavior applies to.
 * @param description What changed, as the warning about patches built for an older version describes it.
 * @param affectsPatch Whether the behavior can change what a patch does.
 */
internal enum class PatcherBehavior(
    since: String,
    val description: String,
    private val affectsPatch: (Patch<*>) -> Boolean,
) {
    /**
     * Resources are decoded when a patch requests them, instead of all before patches execute.
     */
    ON_REQUEST_RESOURCE_DECODING(
        // Morphe Patcher 2.0.0, including its pre-releases such as 2.0.0-dev.1, so patches can be tested
        // with it before the release.
        since = "2.0.0-0",
        description = "Resources are decoded when patches request them, instead of all before patches execute",
        affectsPatch = { patch -> patch.anyRecursively { it is ResourcePatch } },
    ),
    ;

    val since = PatcherVersion.parse(since) ?: throw IllegalStateException("Invalid version: $since")

    /**
     * @param version The version whose behavior patching uses, or null if it is unknown, which is the
     * behavior of the oldest version.
     */
    fun appliesTo(version: PatcherVersion?) = version != null && version >= since

    fun affects(patch: Patch<*>) = affectsPatch(patch)
}

/**
 * The version of Morphe Patcher whose behavior patching uses: [configured] if a host set one, otherwise
 * the newest version any of the patch bundles of [patches] was built for. Patches that were not loaded
 * from a patch bundle have no say in it.
 *
 * Patches of a bundle built for an older version may not work as intended with the behavior of a newer
 * one, so each such bundle whose patches a newer behavior affects is logged as a warning.
 *
 * @return The version, or null to use the behavior of the oldest version.
 */
internal fun targetPatcherVersion(
    configured: String?,
    patches: Collection<Patch<*>>,
    logger: Logger,
): PatcherVersion? {
    val patchesByBundle = patches.filter { it.bundle != null }.groupBy { it.bundle!! }

    val target = if (configured != null) {
        PatcherVersion.parse(configured).also {
            if (it == null) logger.warning("Ignoring the invalid target Morphe Patcher version \"$configured\"")
        }
    } else {
        patchesByBundle.keys.mapNotNull { PatcherVersion.parse(it.patcherVersion) }.maxOrNull()
    }

    warnAboutOutdatedBundles(target, patchesByBundle, logger)

    return target
}

private fun warnAboutOutdatedBundles(
    target: PatcherVersion?,
    patchesByBundle: Map<PatchBundleInfo, List<Patch<*>>>,
    logger: Logger,
) {
    val behaviors = PatcherBehavior.entries.filter { it.appliesTo(target) }
    if (behaviors.isEmpty()) return

    val outdated = patchesByBundle.mapNotNull { (bundle, patches) ->
        val version = PatcherVersion.parse(bundle.patcherVersion)
        val changed = behaviors.filter { behavior ->
            !behavior.appliesTo(version) && patches.any(behavior::affects)
        }
        if (changed.isEmpty()) null else bundle to changed
    }
    if (outdated.isEmpty()) return

    val newest = patchesByBundle.keys.filter { PatcherVersion.parse(it.patcherVersion) == target }

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
