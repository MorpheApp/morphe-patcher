/*
 * Code hard forked from:
 * https://github.com/revanced/revanced-library/tree/06733072045c8016a75f232dec76505c0ba2e1cd
 */

package app.morphe.patcher.apk

import app.morphe.patcher.PatcherResult
import app.morphe.patcher.apk.ApkSigner.newApkSigner
import app.morphe.patcher.apk.ApkSigner.newKeyStore
import app.morphe.patcher.apk.ApkSigner.newPrivateKeyCertificatePair
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkFormatException
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.CompressionMethod
import com.android.tools.build.apkzlib.zip.DataDescriptorType
import com.android.tools.build.apkzlib.zip.StoredEntry
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.android.tools.build.apkzlib.zip.compress.DeflateExecutionCompressor
import com.reandroid.apk.ApkModule
import com.reandroid.archive.writer.ZipAligner
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.NoSuchAlgorithmException
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ForkJoinPool
import java.util.logging.Logger
import kotlin.time.Duration.Companion.days

/**
 * Utility functions to work with APK files.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object ApkUtils {
    private val logger = Logger.getLogger(ApkUtils::class.java.name)

    private const val LIBRARY_EXTENSION = ".so"

    // Alignment for native libraries. A device with 16 KiB pages maps a stored library only
    // from a 16 KiB boundary, and the larger boundary is also a 4 KiB one.
    private const val LIBRARY_ALIGNMENT = 1024 * 16

    // Alignment for all other files.
    private const val DEFAULT_ALIGNMENT = 4

    // Within a few percent of the default level's size in markedly less time.
    private const val COMPRESSION_LEVEL = 3

    private val dexEntryName = Regex("""classes(?:\d+)?\.dex""")

    // v1 signature files, which the signer would otherwise leave stale beside its own signature.
    private val v1SignatureEntryName = Regex("""META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]*)""", RegexOption.IGNORE_CASE)

    private val zFileOptions =
        ZFileOptions().setAlignmentRule(
            AlignmentRules.compose(
                AlignmentRules.constantForSuffix(LIBRARY_EXTENSION, LIBRARY_ALIGNMENT),
                AlignmentRules.constant(DEFAULT_ALIGNMENT),
            ),
        ).setCompressor(DeflateExecutionCompressor(ForkJoinPool.commonPool(), COMPRESSION_LEVEL))

    /**
     * Writes this module to [file] aligned as [applyTo] aligns, so that it has nothing left to move.
     */
    internal fun ApkModule.writeAlignedApk(file: File) = createApkFileWriter(file).apply {
        zipAligner = ZipAligner().apply {
            setDefaultAlignment(DEFAULT_ALIGNMENT)
            setFileAlignment({ it.endsWith(LIBRARY_EXTENSION) }, LIBRARY_ALIGNMENT)
        }
    }.write()

    /**
     * Applies the [PatcherResult] to the given [apkFile].
     *
     * The order of operation is as follows:
     * 1. Use resources.apk compiled by AAPT as the output base, when present.
     * 2. Delete resources staged for deletion.
     * 3. Write raw resources. This comes after the deletion so that an entry a patch deleted and
     *    then recreated ends up with the recreated content rather than removed.
     * 4. Write patched dex files.
     * 5. Realign the APK.
     *
     * Without a compiled resource APK, [apkFile] must be a copy of the input APK so that
     * unchanged entries remain available. Otherwise, the compiled resource APK replaces it.
     * Either way it holds the input's DEX files, which a complete patched DEX set replaces and
     * a partial one overwrites by name. v1 signature files are dropped, as signing replaces them.
     *
     * @param apkFile A copy of the patched APK, to apply the patched files to.
     */
    fun PatcherResult.applyTo(apkFile: File) {
        resources.resourcesApk?.let { resourcesApk ->
            logger.info("Using compiled resource APK as output base")

            if (resourcesApk.canonicalFile != apkFile.canonicalFile) {
                resourcesApk.copyTo(apkFile, overwrite = true)
            }
        }

        ZFile.openReadWrite(apkFile, zFileOptions).use { targetApkZFile ->
            rewriteDataDescriptorEntries(targetApkZFile)
            // Entries are compressed in the background and cannot be deleted or moved until done.
            targetApkZFile.finishAllBackgroundTasks()

            val replacesAllDexFiles = dexFilesComplete && dexFiles.isNotEmpty()
            targetApkZFile.entries().filter { entry ->
                val name = entry.centralDirectoryHeader.name
                name.matches(v1SignatureEntryName) || replacesAllDexFiles && name.matches(dexEntryName)
            }.forEach(StoredEntry::delete)

            resources.let { resources ->
                // Delete resources that were staged for deletion, before adding the raw resources:
                // everything in otherResources is the newest version of its entry by construction.
                if (resources.deleteResources.isNotEmpty()) {
                    targetApkZFile.entries().filter { entry ->
                        entry.centralDirectoryHeader.name in resources.deleteResources
                    }.forEach(StoredEntry::delete)
                }

                // Add resources not compiled by AAPT.
                resources.otherResources?.let { otherResources ->
                    targetApkZFile.addAllRecursively(otherResources) { file ->
                        file.relativeTo(otherResources).invariantSeparatorsPath !in resources.doNotCompress
                    }
                }
            }

            // Run this after resource updates to ensure our dex files don't get overwritten.
            try {
                dexFiles.forEach { dexFile ->
                    targetApkZFile.add(dexFile.name, dexFile.stream)
                }
            } finally {
                dexFiles.forEach { dexFile ->
                    runCatching { dexFile.stream.close() }
                }
            }

            targetApkZFile.finishAllBackgroundTasks()

            logger.info("Aligning APK")

            targetApkZFile.realign()

            logger.fine("Writing changes")
        }
    }

    /**
     * Rewrites every entry that was written with a data descriptor (general purpose bit 3).
     *
     * Once the archive changes, apkzlib writes each central directory header again, without
     * bit 3, but it leaves the local header of an entry it does not move as it was, bit 3 and
     * zeroed sizes included. The headers then disagree and apksig refuses to sign the APK. Old
     * build tools wrote deflated entries this way, and entries reused from such an input keep it.
     * Adding the entry again makes apkzlib write both headers itself.
     */
    private fun rewriteDataDescriptorEntries(zFile: ZFile) {
        zFile.entries()
            .filter { it.dataDescriptorType != DataDescriptorType.NO_DATA_DESCRIPTOR }
            .forEach { entry ->
                val name = entry.centralDirectoryHeader.name
                val deflated = entry.centralDirectoryHeader.compressionInfoWithWait.method ==
                        CompressionMethod.DEFLATE
                val contents = entry.read()
                zFile.add(name, contents.inputStream(), deflated)
            }
    }

    /**
     * Creates a new private key and certificate pair and saves it to the keystore in [keyStoreDetails].
     *
     * @param privateKeyCertificatePairDetails The details for the private key and certificate pair.
     * @param keyStoreDetails The details for the keystore.
     *
     * @return The newly created private key and certificate pair.
     */
    internal fun newPrivateKeyCertificatePair(
        privateKeyCertificatePairDetails: PrivateKeyCertificatePairDetails,
        keyStoreDetails: KeyStoreDetails,
    ) = newPrivateKeyCertificatePair(
        privateKeyCertificatePairDetails.commonName,
        privateKeyCertificatePairDetails.validUntil,
    ).also { privateKeyCertificatePair ->
        val keyStoreFile = keyStoreDetails.keyStore
        // Written beside the target and moved over it, so an interrupted write cannot leave a
        // truncated keystore that the next run takes for an existing one
        val stagingFile = File(keyStoreFile.absoluteFile.parentFile, "${keyStoreFile.name}.tmp")
        try {
            stagingFile.outputStream().use { stream ->
                newKeyStore(
                    setOf(
                        ApkSigner.KeyStoreEntry(
                            keyStoreDetails.alias,
                            keyStoreDetails.password,
                            privateKeyCertificatePair,
                        ),
                    ),
                ).store(stream, keyStoreDetails.keyStorePassword?.toCharArray())
            }
            try {
                Files.move(
                    stagingFile.toPath(),
                    keyStoreFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(stagingFile.toPath(), keyStoreFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            stagingFile.delete()
        }
    }

    /**
     * Reads the private key and certificate pair from an existing keystore.
     *
     * @param keyStoreDetails The details for the keystore.
     *
     * @return The private key and certificate pair.
     */
    internal fun readPrivateKeyCertificatePairFromKeyStore(
        keyStoreDetails: KeyStoreDetails,
    ) = ApkSigner.readPrivateKeyCertificatePair(
        keyStoreDetails.keyStore.inputStream().use { stream ->
            ApkSigner.readKeyStore(stream, keyStoreDetails.keyStorePassword)
        },
        keyStoreDetails.alias,
        keyStoreDetails.password,
    )

    /**
     * Signs [inputApkFile] with the given options and saves the signed apk to [outputApkFile].
     * If [KeyStoreDetails.keyStore] does not exist,
     * a new private key and certificate pair will be created and saved to the keystore.
     *
     * @param inputApkFile The apk file to sign.
     * @param outputApkFile The file to save the signed apk to. Passing [inputApkFile] signs it in place.
     * @param signer The name of the signer.
     * @param keyStoreDetails The details for the keystore.
     * @param minSdkVersion The lowest API level the APK will be installed on,
     *   if higher than the minimum it declares. From API level 24 no v1 signature is needed.
     */
    @JvmOverloads
    fun signApk(
        inputApkFile: File,
        outputApkFile: File,
        signer: String,
        keyStoreDetails: KeyStoreDetails,
        minSdkVersion: Int = 0,
    ) = newApkSigner(
        signer,
        if (keyStoreDetails.keyStore.exists()) {
            readPrivateKeyCertificatePairFromKeyStore(keyStoreDetails)
        } else {
            newPrivateKeyCertificatePair(PrivateKeyCertificatePairDetails(), keyStoreDetails)
        },
    ).signApk(inputApkFile, outputApkFile, minSdkVersion)

    /**
     * Verifies the signature of [apkFile] as a device running [platformVersion] checks it on
     * install, and returns the certificates the APK is signed with.
     *
     * A signer that rotated its key reports its whole lineage, oldest first, as Android reports
     * it for an installed package, so a certificate the app was once signed with still matches.
     *
     * @param apkFile The APK file to verify.
     * @param platformVersion The Android API level to verify for, such as `Build.VERSION.SDK_INT`.
     *
     * @return The certificates of [apkFile], an empty list if it is unsigned, malformed or its
     *   signature does not verify, or null if it could not be read, which a later attempt may not repeat.
     */
    fun verifiedSigningCertificates(apkFile: File, platformVersion: Int): List<X509Certificate>? =
        try {
            val result = ApkVerifier.Builder(apkFile)
                .setMinCheckedPlatformVersion(platformVersion)
                .setMaxCheckedPlatformVersion(platformVersion)
                .build()
                .verify()

            if (result.isVerified) {
                result.signingCertificateLineage?.certificatesInLineage ?: result.signerCertificates
            } else {
                emptyList()
            }
        } catch (_: ApkFormatException) {
            emptyList()
        } catch (e: IOException) {
            logger.warning("Failed to read $apkFile for verification: $e")
            null
        } catch (e: NoSuchAlgorithmException) {
            logger.warning("Failed to verify $apkFile: $e")
            null
        }

    /**
     * Details for a keystore.
     *
     * @param keyStore The file to save the keystore to.
     * @param keyStorePassword The password for the keystore.
     * @param alias The alias of the key store entry to use for signing.
     * @param password The password for recovering the signing key.
     */
    class KeyStoreDetails(
        val keyStore: File,
        val keyStorePassword: String? = null,
        val alias: String,
        val password: String,
    )

    /**
     * Details for a private key and certificate pair.
     *
     * @param commonName The common name for the certificate saved in the keystore.
     * @param validUntil The date until which the certificate is valid.
     */
    class PrivateKeyCertificatePairDetails(
        val commonName: String = "Morphe",
        val validUntil: Date = Date(System.currentTimeMillis() + (365.days * 8).inWholeMilliseconds * 24),
    )
}
