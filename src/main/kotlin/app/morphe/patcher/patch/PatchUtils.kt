/*
 * Code hard forked from:
 * https://github.com/revanced/revanced-library/tree/06733072045c8016a75f232dec76505c0ba2e1cd
 */

package app.morphe.patcher.patch

import java.util.logging.Logger

typealias Count = Int
typealias VersionMap = LinkedHashMap<VersionName, Count>
typealias PackageNameMap = Map<PackageName, VersionMap>
typealias PatchName = String
typealias OptionKey = String
typealias OptionValue = Any?
typealias PatchesOptions = Map<PatchName, Map<OptionKey, OptionValue>>

private val logger = Logger.getLogger("PatchUtils")

/**
 * Get the count of versions for each compatible package from the set of [Patch] ordered by the most common version.
 *
 * @param packageNames The names of the compatible packages to include. If null, all packages will be included.
 * @param countUnusedPatches Whether to count patches that are not used.
 * @return A map of package names to a map of versions to their count.
 */
fun Set<Patch<*>>.mostCommonCompatibleVersions(
    packageNames: Set<String>? = null,
    countUnusedPatches: Boolean = false,
): PackageNameMap = buildMap {
    fun filterWantedPackages(compatiblePackages: List<Package>): List<Package> {
        val wantedPackages = packageNames?.toHashSet() ?: return compatiblePackages
        return compatiblePackages.filter { (name, _) -> name in wantedPackages }
    }

    this@mostCommonCompatibleVersions.filter { it.default || countUnusedPatches }
        .flatMap { it.compatiblePackages ?: emptyList() }
        .let(::filterWantedPackages)
        .forEach { (name, versions) ->
            if (versions?.isEmpty() == true) {
                return@forEach
            }

            val versionMap = getOrPut(name) { linkedMapOf() }

            versions?.forEach { version ->
                versionMap[version] = versionMap.getOrDefault(version, 0) + 1
            }
        }

    // Sort the version maps by the most common version.
    forEach { (packageName, versionMap) ->
        this[packageName] =
            versionMap
                .asIterable()
                .sortedWith(compareByDescending { it.value })
                .associate { it.key to it.value } as VersionMap
    }
}

/**
 * Set the options for a set of patches that have a name.
 *
 * @param options The options to set. The key is the patch name and the value is a map of option keys to option values.
 */
fun Set<Patch<*>>.setOptions(options: PatchesOptions) = filter { it.name != null }.forEach { patch ->
    options[patch.name]?.forEach setOption@{ (optionKey, optionValue) ->
        if (optionKey !in patch.options) {
            return@setOption logger.warning(
                "Could not set option for the \"${patch.name}\" patch because " +
                        "option with key \"${optionKey}\" does not exist",
            )
        }

        try {
            patch.options[optionKey] = optionValue
        } catch (e: OptionException) {
            logger.warning("Could not set option value for the \"${patch.name}\" patch: ${e.message}")
        }
    }
}
