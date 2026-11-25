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
     * Decode all DEX files, but generate new DEX files only for modified/new classes and strip the original metadata
     * from the original APKs. Fastest with lowest memory requirements, but bloats code size.
     */
    STRIP_FAST,

    /**
     * Decode all DEX files, but generate new DEX files only for modified/new classes. Completely strip the original
     * classes from the original APKs. More space efficient than [STRIP_FAST], but slower and more memory intensive.
     */
    STRIP_SAFE,

    /**
     * Do not decode or compile any bytecode.
     */
    NONE,
}