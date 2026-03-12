/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

private val SHA_256_REGEX = Regex("^[0-9a-fA-F]{64}$")

enum class ApkFileType {
    APK,
    APKM,
    XAPK
}

/**
 * @param version Version string. Null means any version and can be used to indicate any version
 *   is supported experimentally.
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
 * @param appIconColor #RRGGBB color for the app icon background color. Only used for Manager UI presentation.
 *   Color int has full 0xFF opacity value.
 * @param signatures Valid SHA-256 signatures of the app. To find a signature, use
 *   `apksigner verify --print-certs` on an original apk (or base.apk from an unzipped apkm)
 *    and `certificate SHA-256 digest:` is the signature.
 * @param targets App targets. Null means any version. Versions are declared newest to oldest.
 */
data class Compatibility(
    val packageName: String? = null,
    val name: String? = null,
    val description: String? = null,
    val apkFileType: ApkFileType? = null,
    val appIconColor: Int? = null,
    val signatures: Set<String>? = null,
    val targets: List<AppTarget>? = null,
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

        if (targets != null) {
            if (targets.isEmpty()) {
                throw IllegalArgumentException(
                    "Compatibility list is empty. If compatibility is for any version then" +
                            " use NULL targets parameter."
                )
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
    }

    constructor(
        name: String? = null,
        apkFileType: ApkFileType? = null,
        appIconColor: String,
        description: String? = null,
        targets: List<AppTarget>? = null,
    ) : this(
        name = name,
        apkFileType = apkFileType,
        appIconColor = parseColor(appIconColor),
        description = description,
        targets = targets
    )

    internal fun toLegacy(): Pair<String, Set<String>?>? {
        if (packageName == null) return null

        val legacyTargets = mutableSetOf<String>()

        val includeExperimental = targets?.none { !it.isExperimental } == true

        targets?.forEach { target ->
            // If the declaration only has experimental, then include experimental with legacy versions.
            if (target.version != null && (includeExperimental || !target.isExperimental)) {
                legacyTargets += target.version
            }
        }

        return packageName to legacyTargets.ifEmpty { null }
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

            legacy.second?.forEach { version ->
                targets += AppTarget(version = version)
            }

            return Compatibility(packageName = legacy.first, targets = targets.ifEmpty { null })
        }

        fun fromLegacy(legacy: Set<Pair<String, Set<String>?>>?): List<Compatibility>? {
            if (legacy == null) return null

            return legacy.map { pair ->
                fromLegacy(pair)
            }
        }
    }
}
