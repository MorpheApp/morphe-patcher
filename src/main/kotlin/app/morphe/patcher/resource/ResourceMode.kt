/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.resource

/**
 * How to handle resources decoding and compiling.
 */
enum class ResourceMode {
    /**
     * Decode and compile all resources.
     */
    FULL,

    /**
     * Only extract resources from the APK.
     * The AndroidManifest.xml and resources inside /res are not decoded or compiled.
     */
    RAW_ONLY,

    /**
     * Do not decode or compile any resources.
     */
    NONE,
}