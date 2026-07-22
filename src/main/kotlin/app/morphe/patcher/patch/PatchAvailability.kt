/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

/**
 * The kind of install target the patched app is being produced for.
 *
 * Patches use this together with [ApkArchitecture] to declare whether they are
 * available, forbidden or required for a given install target, via [AvailabilityResolver].
 *
 * Callers (Manager / CLI) are responsible for translating their own installer taxonomy
 * (session install, mount install, Shizuku, etc.) into one of these values before
 * asking a patch about its availability.
 */
enum class InstallerType {
    /** A regular session-based install. GmsCore is required for Google apps. */
    STANDARD,

    /** A root install performed by bind-mounting the patched APK over the original. */
    MOUNT,

    /**
     * An elevated non-root install performed via Shizuku. Behaves like [STANDARD]
     * for most patches; separated so a patch can differentiate if needed.
     */
    SHIZUKU,
}

/**
 * The primary architecture of the target APK the patched app is being produced for.
 *
 * Passed to [AvailabilityResolver] together with [InstallerType]. When the input APK
 * bundles multiple ABIs, callers should pass the highest-priority ABI actually present,
 * or [UNIVERSAL] when the APK targets all ABIs.
 */
enum class ApkArchitecture {
    ARM64_V8A,
    ARMEABI_V7A,
    X86_64,
    X86,
    UNIVERSAL,
}

/**
 * How a patch should behave for a given (installer, architecture) combination.
 *
 * A patch that does not declare an [AvailabilityResolver] is treated as [ENABLED] when
 * its `default` flag is true and [DISABLED] otherwise, preserving legacy behavior.
 */
enum class PatchAvailability {
    /** Enabled by default. The user may disable it. */
    ENABLED,

    /** Disabled by default. The user may enable it. */
    DISABLED,

    /** Force-included. The user cannot remove it. */
    REQUIRED,

    /** Force-excluded. The user cannot add it. */
    UNAVAILABLE,
}

/**
 * A pure function stored on a patch that decides the patch's [PatchAvailability]
 * for a given ([InstallerType], [ApkArchitecture]) combination.
 *
 * Kept as a lambda (rather than static fields) so a single patch can express
 * matrix-like rules without inflating the metadata surface.
 *
 * Implementations must be side-effect free and deterministic. Manager and CLI may call
 * them repeatedly while the user changes selection or install mode.
 */
typealias AvailabilityResolver = (InstallerType, ApkArchitecture) -> PatchAvailability

/**
 * Returns an [AvailabilityResolver] that ignores its inputs and derives the result
 * purely from a static [default] flag. Provided as a convenience for patches that
 * want to opt into the new API without adding target-specific logic.
 */
fun defaultAvailability(default: Boolean): AvailabilityResolver = { _, _ ->
    if (default) PatchAvailability.ENABLED else PatchAvailability.DISABLED
}
