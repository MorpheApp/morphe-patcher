/*
 * Code hard forked from:
 * https://github.com/revanced/revanced-library/tree/06733072045c8016a75f232dec76505c0ba2e1cd
 */

package app.morphe.patcher.apk

import app.morphe.patcher.PatcherResult
import app.morphe.patcher.apk.ApkSigner.newApkSigner
import app.morphe.patcher.apk.ApkSigner.newKeyStore
import app.morphe.patcher.apk.ApkSigner.newPrivateKeyCertificatePair
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.StoredEntry
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import java.io.File
import java.util.*
import java.util.logging.Logger
import kotlin.time.Duration.Companion.days

/**
 * Utility functions to work with APK files.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object ApkUtils {
    private val logger = Logger.getLogger(ApkUtils::class.java.name)

    private const val LIBRARY_EXTENSION = ".so"

    // Alignment for native libraries.
    private const val LIBRARY_ALIGNMENT = 1024 * 4

    // Alignment for all other files.
    private const val DEFAULT_ALIGNMENT = 4

    private val dexEntryName = Regex("""classes(?:\d+)?\.dex""")

    private val zFileOptions =
        ZFileOptions().setAlignmentRule(
            AlignmentRules.compose(
                AlignmentRules.constantForSuffix(LIBRARY_EXTENSION, LIBRARY_ALIGNMENT),
                AlignmentRules.constant(DEFAULT_ALIGNMENT),
            ),
        )

    /**
     * Applies the [PatcherResult] to the given [apkFile].
     *
     * The order of operation is as follows:
     * 1. Use resources.apk compiled by AAPT as the output base, when present.
     * 2. Write raw resources.
     * 3. Delete resources staged for deletion.
     * 4. Write patched dex files.
     * 5. Realign the APK.
     *
     * Without a compiled resource APK, [apkFile] must be a copy of the input APK so that
     * unchanged entries remain available. Otherwise, the compiled resource APK replaces it.
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
            resources.let { resources ->
                // A compiled resource APK is a complete non-DEX APK. Remove any accidentally packaged DEX files
                // before adding the final patched DEX set.
                if (resources.resourcesApk != null) {
                    targetApkZFile.entries().filter { entry ->
                        entry.centralDirectoryHeader.name.matches(dexEntryName)
                    }.forEach(StoredEntry::delete)
                }

                // Add resources not compiled by AAPT.
                resources.otherResources?.let { otherResources ->
                    targetApkZFile.addAllRecursively(otherResources) { file ->
                        file.relativeTo(otherResources).invariantSeparatorsPath !in resources.doNotCompress
                    }
                }

                // Delete resources that were staged for deletion.
                if (resources.deleteResources.isNotEmpty()) {
                    targetApkZFile.entries().filter { entry ->
                        entry.centralDirectoryHeader.name in resources.deleteResources
                    }.forEach(StoredEntry::delete)
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

            logger.info("Aligning APK")

            targetApkZFile.realign()

            logger.fine("Writing changes")
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
    private fun newPrivateKeyCertificatePair(
        privateKeyCertificatePairDetails: PrivateKeyCertificatePairDetails,
        keyStoreDetails: KeyStoreDetails,
    ) = newPrivateKeyCertificatePair(
        privateKeyCertificatePairDetails.commonName,
        privateKeyCertificatePairDetails.validUntil,
    ).also { privateKeyCertificatePair ->
        newKeyStore(
            setOf(
                ApkSigner.KeyStoreEntry(
                    keyStoreDetails.alias,
                    keyStoreDetails.password,
                    privateKeyCertificatePair,
                ),
            ),
        ).store(
            keyStoreDetails.keyStore.outputStream(),
            keyStoreDetails.keyStorePassword?.toCharArray(),
        )
    }

    /**
     * Reads the private key and certificate pair from an existing keystore.
     *
     * @param keyStoreDetails The details for the keystore.
     *
     * @return The private key and certificate pair.
     */
    private fun readPrivateKeyCertificatePairFromKeyStore(
        keyStoreDetails: KeyStoreDetails,
    ) = ApkSigner.readPrivateKeyCertificatePair(
        ApkSigner.readKeyStore(
            keyStoreDetails.keyStore.inputStream(),
            keyStoreDetails.keyStorePassword,
        ),
        keyStoreDetails.alias,
        keyStoreDetails.password,
    )

    /**
     * Signs [inputApkFile] with the given options and saves the signed apk to [outputApkFile].
     * If [KeyStoreDetails.keyStore] does not exist,
     * a new private key and certificate pair will be created and saved to the keystore.
     *
     * @param inputApkFile The apk file to sign.
     * @param outputApkFile The file to save the signed apk to.
     * @param signer The name of the signer.
     * @param keyStoreDetails The details for the keystore.
     */
    fun signApk(
        inputApkFile: File,
        outputApkFile: File,
        signer: String,
        keyStoreDetails: KeyStoreDetails,
    ) = newApkSigner(
        signer,
        if (keyStoreDetails.keyStore.exists()) {
            readPrivateKeyCertificatePairFromKeyStore(keyStoreDetails)
        } else {
            newPrivateKeyCertificatePair(PrivateKeyCertificatePairDetails(), keyStoreDetails)
        },
    ).signApk(inputApkFile, outputApkFile)

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
