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

    // Prefix for resources.
    private const val RES_PREFIX = "res/"

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
     * 1. Write patched dex files.
     * 2. Delete all resources in the target APK
     * 3. Merge resources.apk compiled by AAPT.
     * 4. Write raw resources.
     * 5. Delete resources staged for deletion.
     * 6. Realign the APK.
     *
     * @param apkFile The file to apply the patched files to.
     */
    fun PatcherResult.applyTo(apkFile: File) {
        ZFile.openReadWrite(apkFile, zFileOptions).use { targetApkZFile ->
            dexFiles.forEach { dexFile ->
                targetApkZFile.add(dexFile.name, dexFile.stream)
                dexFile.stream.close()
            }

            resources?.let { resources ->
                // Add resources compiled by AAPT.
                resources.resourcesApk?.let { resourcesApk ->
                    ZFile.openReadOnly(resourcesApk).use { resourcesApkZFile ->
                        // Delete all resources in the target APK before merging the new ones.
                        // This is necessary because the resources.apk renames resources.
                        // So unless, the old resources are deleted, there will be orphaned resources in the APK.
                        // It is not necessary, but for the sake of cleanliness, it is done.
                        targetApkZFile.entries().filter { entry ->
                            entry.centralDirectoryHeader.name.startsWith(RES_PREFIX)
                        }.forEach(StoredEntry::delete)

                        targetApkZFile.mergeFrom(resourcesApkZFile) { false }
                    }
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
        val alias: String = "Morphe Key",
        val password: String = "",
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
