/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

/**
 * How to handle bytecode decoding and compiling.
 */
enum class BytecodeMode {
    /**
     * Decode and compile all DEX files, creating completely new APK files afterwards. Slowest and most memory intensive,
     * but the most space efficient.
     */
    FULL,

    /**
     * Decode all DEX files, but generate new DEX files only for modified/new classes and strip the original metadata
     * from the original APKs. Fastest with lowest memory requirements, but bloats code size.
     */
    STRIP_FAST,

    /**
     * Decode all DEX files, but generate new DEX files only for modified/new classes. Completely strip the original
     * classes from the original APKs. Faster and less memory intensive than [FULL] while being more space efficient than
     * [STRIP_FAST].
     */
    STRIP_SAFE,

    /**
     * Do not decode or compile any bytecode.
     */
    NONE,
}