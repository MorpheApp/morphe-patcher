/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.profile

import app.morphe.patcher.apk.ApkUtils
import java.io.File
import java.util.zip.ZipFile

/**
 * The baseline profile entries of the patched APK, which replace every one of the input APK, see
 * [BaselineProfile.ENTRIES]. A profile that no longer matches the DEX files is left out rather than kept,
 * since ART would not use it.
 *
 * @param entries The entries written, by name, which the patched APK stores.
 */
internal class ProfileEntries(val entries: Map<String, ByteArray>) {
    companion object {
        /** Leaves out the profile of the input APK, for DEX files it can no longer describe. */
        val LEFT_OUT = ProfileEntries(emptyMap())

        fun of(profile: BaselineProfile) = ProfileEntries(profile.toApkEntries())
    }
}

/**
 * The baseline profile of an APK and the CRC-32 of its DEX entries, which the profile records.
 *
 * @param profile The profile, or null if the APK has none.
 * @param hasProfileEntries Whether the APK has any of [BaselineProfile.ENTRIES], which [profile] reads only one of.
 */
internal class ApkProfile(
    val profile: BaselineProfile?,
    val dexChecksums: Map<String, Int>,
    val hasProfileEntries: Boolean,
) {
    companion object {
        /**
         * Reads the profile of [apk].
         *
         * @throws IllegalArgumentException If the profile cannot be read.
         */
        fun read(apk: File): ApkProfile = ZipFile(apk).use { zip ->
            val dexChecksums = zip.entries().asSequence()
                .filter { it.name.matches(ApkUtils.dexEntryName) }
                .associate { it.name to it.crc.toInt() }
            val profile = zip.getEntry(BaselineProfile.PROFILE_ENTRY)?.let { entry ->
                BaselineProfile.read(zip.getInputStream(entry).use { it.readBytes() })
            }
            ApkProfile(profile, dexChecksums, BaselineProfile.ENTRIES.any { zip.getEntry(it) != null })
        }

        /** Whether [apk] has a baseline profile entry, readable or not. */
        fun hasProfile(apk: File) = ZipFile(apk).use { zip ->
            BaselineProfile.ENTRIES.any { zip.getEntry(it) != null }
        }
    }
}
