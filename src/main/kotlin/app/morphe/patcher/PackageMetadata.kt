/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher

/**
 * Metadata about a package.
 *
 * @param packageName The name of the package.
 * @param versionCode The version code of the package.
 * @param versionName The version name of the package.
 */
class PackageMetadata internal constructor(
    val packageName: String,
    val versionName: String,
    val versionCode: String,
)
