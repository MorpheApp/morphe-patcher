/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

import kotlin.collections.all
import kotlin.collections.isNotEmpty

private val SHA_256_REGEX = Regex("^[0-9a-fA-F]{64}$")

/**
 * Original app file type.
 *
 * Serves two purposes:
 * 1. Indicate the preferred/default file type for Manager UI presentation.
 * 2. Indicates a required file type that must be used and all other types may
 *    fail to patch or are undesirable to use.
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

enum class SupportedAbi {
    ARM64_V8A,
    ARMEABI_V7A,
    X86_64,
    X86
}

/**
 * Instances are sortable from lowest to highest version, with any version (null) last.
 * Semantic versioning is handled and sorts correctly in situations such as 1.1.0 > 1.0.02
 * Non-semantic versioning is sorted alphabetically.
 *
 * @param version Version string. Null means any version and additionally can be used to
 *   indicate any version is supported experimentally.
 *  @param versionCodes Required app version codes. If the map is null, or an architecture
 *   key value is null, then any app version is assumed to work. This declaration is only required
 *   for apps that can have multiple releases under the same user facing version string (ie: 5.2.1)
 *   but only one specific release is supported or recommended. This is common with Meta apps
 *   but uncommon with most other apps.
 * @param isExperimental If this app target is supported under an experimental capacity.
 * @param minSdk Minimum device SDK version as found in [android.os.Build.VERSION_CODES].
 *   Null means any SDK version.
 * @param description User facing description of the app target, such as why the user may want
 *   to patch this specific app version.
 */
data class AppTarget(
    val version: String?,
    val versionCodes: Map<SupportedAbi, Int>? = null,
    val isExperimental: Boolean = false,
    val minSdk: Int? = null,
    val description: String? = null
) : Comparable<AppTarget> {

    private val semanticParts: List<Int>? = parseSemantic(version)

    /**
     * Convenience constructor for a universal APK app target,
     * where a specific app version is required.
     *
     * @param versionCode Specific required app version code.
     */
    constructor(
        version: String,
        versionCode: Int,
        isExperimental: Boolean = false,
        minSdk: Int? = null,
        description: String? = null,
    ) : this(
        version = version,
        versionCodes = SupportedAbi.entries.associateWith { versionCode },
        isExperimental = isExperimental,
        minSdk = minSdk,
        description = null
    )

    // @Deprecated("Here only for binary backwards compatibility") // TODO: Remove after next major version bump.
    constructor(
        version: String?,
        isExperimental: Boolean = false,
        minSdk: Int? = null,
    ) : this(
        version = version,
        versionCodes = null,
        isExperimental = isExperimental,
        minSdk = minSdk,
        description = null
    )

    // @Deprecated("Here only for binary backwards compatibility") // TODO: Remove after next major version bump.
    constructor(
        version: String?,
        isExperimental: Boolean = false,
        minSdk: Int? = null,
        description: String? = null
    ) : this(
        version = version,
        versionCodes = null,
        isExperimental = isExperimental,
        minSdk = minSdk,
        description = null
    )

    init {
        if (version == null && !versionCodes.isNullOrEmpty()) {
            throw IllegalArgumentException("Version codes requires declaring a version string")
        }
    }

    /**
     * Comparison using only the version field.
     */
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

@ConsistentCopyVisibility
data class Compatibility internal constructor(
    val packageName: String?,
    val name: String?,
    val description: String?,
    val apkFileType: ApkFileType?,
    val appIconColor: Int?,
    val signatures: Set<String>?,
    val targets: List<AppTarget>,
    internal val isLegacy: Boolean
) {

    /**
     * @param packageName Actual app package name.
     * @param name Actual app name. Use the same name that appears in the app launcher.
     * @param description User facing description of the app.
     * @param apkFileType Target unpatched app type. A non required type is a recommendation
     *   but not strictly enforced and other types are still accepted.
     * @param appIconColor #RRGGBB color for the app icon background color.
     *   Only used for Manager UI presentation. Color int has full 0xFF opacity value.
     * @param signatures Valid SHA-256 signatures of the app. To find a signature, use
     *   `apksigner verify --print-certs` on an original apk (or base.apk from an unzipped apkm)
     *    and `certificate SHA-256 digest:` is the signature.
     * @param targets App targets. Versions are declared newest to oldest. Default is any app target.
     */
    constructor(
        packageName: String,
        name: String,
        description: String? = null,
        apkFileType: ApkFileType? = null,
        appIconColor: Int? = null,
        signatures: Set<String>? = null,
        targets: List<AppTarget> = listOf(AppTarget(version = null))
    ) : this(
        packageName = packageName,
        name = name,
        description = description,
        apkFileType = apkFileType,
        appIconColor = appIconColor,
        signatures = signatures,
        targets = targets,
        isLegacy = false
    )

    /**
     * Universal patch that can be applied to any ap.
     * @param description User facing description of the app.
     */
    constructor(
        description: String,
    ) : this(
        packageName = null,
        name = null,
        description = description,
        apkFileType = null,
        appIconColor = null,
        signatures = null,
        targets = listOf(AppTarget(version = null)),
        isLegacy = false
    )

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
        targets = targets,
        isLegacy = false
    )

    init {
        if (!isLegacy && packageName != null && name.isNullOrBlank()) {
            throw IllegalArgumentException(
                "If package name is declared then app name must also be declared"
            )
        }
        require(name == null || name.isNotBlank()) {
            "App name must not be blank"
        }

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

    /**
     * This [Compatibility] but with additional [AppTarget] versions.
     */
    fun including(vararg targets: AppTarget): Compatibility {
        return copy(targets = (this.targets + targets).sortedDescending())
    }

    /**
     * This [Compatibility] but excluding all app targets with the
     * specified [AppTarget.version] version strings.
     */
    fun excluding(vararg versions: String?): Compatibility {
        val versionSet = versions.toSet()
        val updatedTargets = targets
            .filter { it.version !in versionSet }
            .ifEmpty { listOf(AppTarget(version = null)) }

        return copy(targets = updatedTargets)
    }

    @Deprecated("Here only for binary backwards compatibility", level = DeprecationLevel.HIDDEN)
    fun copy(
        packageName: String? = this.packageName,
        name: String? = this.name,
        description: String? = this.description,
        apkFileType: ApkFileType? = this.apkFileType,
        appIconColor: Int? = this.appIconColor,
        signatures: Set<String>? = this.signatures,
        targets: List<AppTarget> = this.targets
    ): Compatibility = Compatibility(
        packageName,
        name,
        description,
        apkFileType,
        appIconColor,
        signatures,
        targets,
        isLegacy = isLegacy
    )

    internal companion object {
        private fun parseColor(color: String): Int {
            require(color.startsWith('#') && color.length == 7) {
                "App icon color must be #RRGGBB format: $color"
            }

            return color.removePrefix("#").toInt(16)
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

            return Compatibility(
                packageName = legacy.first,
                name = null,
                description = null,
                apkFileType = null,
                appIconColor = null,
                signatures = null,
                targets = targets,
                isLegacy = true)
        }

        fun fromLegacy(legacy: Set<Pair<String, Set<String>?>>?): List<Compatibility>? {
            if (legacy == null) return null

            return legacy.map { pair ->
                fromLegacy(pair)
            }
        }
    }
}
