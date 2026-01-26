/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher

import com.reandroid.apk.ApkModule

/**
 * Metadata about a package.
 *
 * @param apkInfo The [ApkModule] of the apk file.
 */
class PackageMetadata internal constructor(internal val apkInfo: ApkModule) {
    lateinit var packageName: String
        internal set

    lateinit var packageVersion: String
        internal set
}
