/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.profile

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Dex metadata (`.dm`): a zip with the baseline profile as `primary.prof`, which PackageInstaller accepts
 * beside an APK so that the install compiles the app with `speed-profile`. Lacking a profile, a sideloaded
 * app is installed with `verify` only, and its hot code runs interpreted or JIT-compiled until background
 * dexopt, unless ART service finds the embedded profile of the APK (Android 15 and up).
 */
object DexMetadata {
    /**
     * The dex metadata for installing [apk] as the base APK on a device running API level [sdk].
     * PackageInstaller pairs a `.dm` file with the APK file of the same base name in one session, so
     * `base.apk` takes `base.dm`.
     *
     * @return The dex metadata, or null below Android 9, which reads none, or if the APK has no baseline
     *   profile that matches every DEX file it names.
     * @throws IOException If the APK cannot be read.
     */
    @JvmStatic
    fun of(apk: File, sdk: Int): ByteArray? {
        val version = ArtProfileVersion.forSdk(sdk) ?: return null
        val profile = ZipFile(apk).use { zip -> installedProfile(zip) } ?: return null

        val primary = profile.writeArtProfile(version)
        val output = ByteArrayOutputStream(primary.size + 256)
        ZipOutputStream(output).use { zip ->
            // Stored, as the system maps the profile where it is.
            zip.putNextEntry(ZipEntry(PRIMARY_PROFILE_ENTRY).apply {
                method = ZipEntry.STORED
                size = primary.size.toLong()
                crc = CRC32().apply { update(primary) }.value
            })
            zip.write(primary)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    /**
     * The profile of the APK with the type counts of its DEX files, which `baseline.prof` does not record,
     * or null if it has none or a part of it matches no DEX file, which makes ART reject all of it.
     */
    private fun installedProfile(zip: ZipFile): BaselineProfile? {
        val entry = zip.getEntry(BaselineProfile.PROFILE_ENTRY) ?: return null
        val profile = try {
            BaselineProfile.read(zip.getInputStream(entry).use { it.readBytes() })
        } catch (_: IllegalArgumentException) {
            return null
        }
        val dexFiles = profile.dexFiles.map { dex ->
            val dexEntry = zip.getEntry(dex.name) ?: return null
            if (dexEntry.crc.toInt() != dex.checksum) return null
            // The header holds the id counts, so the rest of the DEX file is not read.
            val ids = try {
                val header = ByteArray(DEX_HEADER_SIZE)
                zip.getInputStream(dexEntry).use { DataInputStream(it).readFully(header) }
                DexIds(ByteBuffer.wrap(header))
            } catch (_: EOFException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (ids.methodCount != dex.methodCount) return null
            DexProfile(
                dex.name, dex.checksum, ids.typeCount, dex.methodCount,
                dex.classes, dex.classDefs, dex.methods, dex.methodFlags,
            )
        }
        return dexFiles.takeIf { it.isNotEmpty() }?.let(::BaselineProfile)
    }

    /** The entry of the dex metadata that ART reads the profile from. */
    private const val PRIMARY_PROFILE_ENTRY = "primary.prof"

    private const val DEX_HEADER_SIZE = 0x70
}
