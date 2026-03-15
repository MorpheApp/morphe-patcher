/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

private val SHA_256_REGEX = Regex("^[0-9a-fA-F]{64}$")

/**
 * Original app file type.
 *
 * Serves two purposes:
 * 1. Indicate the preferred/default file type for Manager UI presentation.
 * 2. Indicates a required file type that must be used and all other types fail to patch
 *    or are undesirable to use.
 */
enum class ApkFileType {
    APK,
    APK_REQUIRED,
    APKM,
    APKM_REQUIRED,
    APKS,
    APKS_REQUIRED,
    XAPK,
    XAPK_REQUIRED;

    val isApk: Boolean
        get() = this == APK || this == APK_REQUIRED

    val isApkM: Boolean
        get() = this == APKM || this == APKM_REQUIRED

    val isApkS: Boolean
        get() = this == APKS || this == APKS_REQUIRED

    val isXApk: Boolean
        get() = this == XAPK || this == XAPK_REQUIRED

    val isRequired: Boolean
        get() = name.endsWith("_REQUIRED")
}

/**
 * Instances are sortable from lowest to highest version, with any version (null) last.
 * Semantic versioning is handled and sorts correctly in situations such as 1.1.0 > 1.0.02
 * Non-semantic versioning is sorted alphabetically.
 *
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
) : Comparable<AppTarget> {

    private val semanticParts: List<Int>? = parseSemantic(version)

    override fun compareTo(other: AppTarget): Int {
        // Null versions come last
        if (version == null && other.version == null) return 0
        if (version == null) return 1
        if (other.version == null) return -1

        // If both are semantic, compare numerically
        if (semanticParts != null && other.semanticParts != null) {
            val maxLen = maxOf(semanticParts.size, other.semanticParts.size)
            for (i in 0 until maxLen) {
                val a = semanticParts.getOrNull(i) ?: 0
                val b = other.semanticParts.getOrNull(i) ?: 0
                if (a != b) return a - b
            }
            return 0
        }

        // Otherwise compare alphabetically
        return version.compareTo(other.version)
    }

    private companion object {
        fun parseSemantic(v: String?): List<Int>? {
            if (v == null) return null
            val parts = v.split(".")
            if (parts.any { it.toIntOrNull() == null }) return null
            return parts.map { it.toInt() }
        }
    }
}

/**
 * @param packageName Actual app package name. Null means this is a universal patch and can
 *   be applied to any app.
 * @param name Actual app name.
 * @param description User facing description of the app.
 * @param apkFileType Target unpatched app type. A non required type is a recommendation
 *   but not strictly enforced and other types are still accepted.
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

    /**
     * Convenience constructor for universal patches.
     *
     * @param name Actual app name.
     * @param description User facing description of the app.
     * @param apkFileType Target unpatched app type. Currently only used for Manager UI presentation.
     * @param targets App targets. Versions are declared newest to oldest.
     */
    constructor(
        name: String? = null,
        description: String? = null,
        apkFileType: ApkFileType? = null,
        targets: List<AppTarget>? = null,
    ) : this(
        packageName = null,
        name = name,
        description = description,
        apkFileType = apkFileType,
        appIconColor = null,
        signatures = null,
        targets = targets ?: listOf(AppTarget(version = null))
    ) {
        require(this.targets.isNotEmpty()) {
            "Targets parameter must be null for all app targets, or must declare " +
                    "an AppTarget with a null version"
        }
    }

    init {
        if (appIconColor != null) {
            val alpha = (appIconColor shr 24) and 0xFF

            require(alpha == 0x00) {
                "App icon color must be 0xRRGGBB format"
            }
        }

        if (packageName == null && targets.isNotEmpty()) {
            require(targets.all { it.version == null }) {
                "Null package name (universal patch) cannot declare any AppTarget versions: $targets"
            }
        }

        signatures?.forEach { sig ->
            require(sig.matches(SHA_256_REGEX)) {
                "Invalid signature SHA-256 fingerprint: $sig"
            }
        }

        require(targets.isNotEmpty()) {
            "Must declare at least one app target. If any app version is supported then use NULL version"
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
                if (it.isNullOrEmpty()) {
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
