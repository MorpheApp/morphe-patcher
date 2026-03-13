/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

private val SHA_256_REGEX = Regex("^[0-9a-fA-F]{64}$")

/**
 * Original app file type.
 * Currently only used for UI presentation.
 */
enum class ApkFileType {
    APK,
    APKM,
    XAPK
    // TODO? Add types to mandate an app must be patched with a specific type like APK_REQUIRED?
}

/**
 * @param version Version string. Null means any version and additionally can be used to
 *   indicate any version is supported experimentally.
 * @param isExperimental If this app target is supported under an experimental capacity.
 * @param minSdk Minimum device SDK version as found in [android.os.Build.VERSION_CODES].
 *   Null means any SDK version.
 */
data class AppTarget(
    val version: String?,
    val isExperimental: Boolean = false,
    val minSdk: Int? = null,
    //val description: String? = null // TODO? Allow version descriptions?
)

/**
 * @param packageName Actual app package name. Null means this is a universal patch and can
 *   be applied to any app.
 * @param name Actual app name.
 * @param description User facing description of the app.
 * @param apkFileType Target unpatched app type. Currently only used for Manager UI presentation.
 * @param appIconColor #RRGGBB color for the app icon background color.
 *   Only used for Manager UI presentation. Color int has full 0xFF opacity value.
 * @param signatures Valid SHA-256 signatures of the app. To find a signature, use
 *   `apksigner verify --print-certs` on an original apk (or base.apk from an unzipped apkm)
 *    and `certificate SHA-256 digest:` is the signature.
 * @param targets App targets. Versions are declared newest to oldest.
 */
data class Compatibility(
    val packageName: String? = null,
    val name: String? = null,
    val description: String? = null,
    val apkFileType: ApkFileType? = null,
    val appIconColor: Int? = null,
    val signatures: Set<String>? = null,
    val targets: List<AppTarget>,
) {

    init {
        if (appIconColor != null) {
            val alpha = (appIconColor shr 24) and 0xFF

            require(alpha == 0x00) {
                "App icon color must be 0xRRGGBB format"
            }
        }

        signatures?.forEach { sig ->
            require(sig.matches(SHA_256_REGEX)) {
                "Invalid signature SHA-256 fingerprint: $sig"
            }
        }

        // Check for duplicate versions.
        val seen = mutableSetOf<String?>()
        targets.forEach { target ->
            if (!seen.add(target.version)) {
                throw IllegalArgumentException(
                    "Duplicate AppTarget for package '$packageName' of version '${target.version}'"
                )
            }
        }
    }

    /**
     * @param packageName Actual app package name. Null means this is a universal patch and can
     *   be applied to any app.
     * @param name Actual app name.
     * @param description User facing description of the app.
     * @param apkFileType Target unpatched app type. Currently only used for Manager UI presentation.
     * @param appIconColor #RRGGBB color for the app icon background color
     *   Only used for Manager UI presentation. Color int has full 0xFF opacity value.
     * @param signatures Valid SHA-256 signatures of the app. To find a signature, use
     *   `apksigner verify --print-certs` on an original apk (or base.apk from an unzipped apkm)
     *    and `certificate SHA-256 digest:` is the signature.
     * @param targets App targets. Versions are declared newest to oldest.
     */
    constructor(
        packageName: String? = null,
        name: String? = null,
        description: String? = null,
        apkFileType: ApkFileType? = null,
        appIconColor: String,
        signatures: Set<String>? = null,
        targets: List<AppTarget>,
    ) : this(
        packageName = packageName,
        name = name,
        description = description,
        apkFileType = apkFileType,
        appIconColor = parseColor(appIconColor),
        signatures = signatures,
        targets = targets
    )

    internal val legacy: Pair<String, Set<String>?>? by lazy {
        if (packageName == null) return@lazy null

        val legacyTargets = mutableSetOf<String>()

        val includeExperimental = targets.none { !it.isExperimental }
        var isAnyVersion = false

        targets.forEach { target ->
            // If the declaration only has experimental, then include experimental with legacy versions.
            if (includeExperimental || !target.isExperimental) {
                if (target.version == null) {
                    // Legacy cannot handle any version and recommend specific versions.
                    // If any version is present then the entire legacy is any version.
                    isAnyVersion = true
                } else {
                    legacyTargets += target.version
                }
            }
        }

        val legacyStringTargets =
            if (isAnyVersion || legacyTargets.isEmpty()) null
            else legacyTargets

        packageName to legacyStringTargets
    }

    internal companion object {
        private fun parseColor(color: String): Int {
            require(color.startsWith('#') && color.length == 7) {
                "App icon color must be #RRGGBB format: $color"
            }

            val rgb = color.removePrefix("#").toInt(16)

            // force full opacity
            return rgb or 0xFF000000.toInt()
        }

        fun fromLegacy(legacy: Pair<String, Set<String>?>): Compatibility {
            val targets = mutableListOf<AppTarget>()

            legacy.second.let {
                if (it == null) {
                    targets += AppTarget(version = null)
                } else {
                    it.forEach { version ->
                        targets += AppTarget(version = version)
                    }
                }
            }

            return Compatibility(packageName = legacy.first, targets = targets)
        }

        fun fromLegacy(legacy: Set<Pair<String, Set<String>?>>?): List<Compatibility>? {
            if (legacy == null) return null

            return legacy.map { pair ->
                fromLegacy(pair)
            }
        }
    }
}
