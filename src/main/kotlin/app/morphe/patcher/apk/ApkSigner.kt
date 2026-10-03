/*
 * Code hard forked from:
 * https://github.com/revanced/revanced-library/tree/06733072045c8016a75f232dec76505c0ba2e1cd
 */

package app.morphe.patcher.apk

import com.android.apksig.DefaultApkSignerEngine
import com.android.apksig.KeyConfig
import com.android.apksig.apk.ApkSigningBlockNotFoundException
import com.android.apksig.util.DataSources
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import java.util.logging.Logger
import com.android.apksig.apk.ApkUtils as ApkSigUtils

/**
 * Utility class for reading or writing keystore files and entries as well as signing APK files.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object ApkSigner {
    private val logger = Logger.getLogger(ApkSigner::class.java.name)

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun newKeyStoreInstance() = KeyStore.getInstance("BKS", BouncyCastleProvider.PROVIDER_NAME)

    /**
     * Create a new keystore with a new keypair.
     *
     * @param entries The entries to add to the keystore.
     *
     * @return The created keystore.
     *
     * @see KeyStoreEntry
     * @see KeyStore
     */
    fun newKeyStore(entries: Set<KeyStoreEntry>): KeyStore {
        logger.fine("Creating keystore")

        return newKeyStoreInstance().apply {
            load(null)

            entries.forEach { entry ->
                // Add all entries to the keystore.
                setKeyEntry(
                    entry.alias,
                    entry.privateKeyCertificatePair.privateKey,
                    entry.password.toCharArray(),
                    arrayOf(entry.privateKeyCertificatePair.certificate),
                )
            }
        }
    }

    /**
     * Read a keystore from the given [keyStoreInputStream].
     *
     * @param keyStoreInputStream The stream to read the keystore from.
     * @param keyStorePassword The password for the keystore.
     *
     * @return The keystore.
     *
     * @throws IllegalArgumentException If the keystore password is invalid.
     *
     * @see KeyStore
     */
    fun readKeyStore(
        keyStoreInputStream: InputStream,
        keyStorePassword: String?,
    ): KeyStore {
        logger.fine("Reading keystore")

        return newKeyStoreInstance().apply {
            try {
                load(keyStoreInputStream, keyStorePassword?.toCharArray())
            } catch (exception: IOException) {
                if (exception.cause is UnrecoverableKeyException) {
                    throw IllegalArgumentException("Invalid keystore password")
                } else {
                    throw exception
                }
            }
        }
    }

    /**
     * Create a new private key and certificate pair.
     *
     * @param commonName The common name of the certificate.
     * @param validUntil The date until which the certificate is valid.
     *
     * @return The newly created private key and certificate pair.
     *
     * @see PrivateKeyCertificatePair
     */
    fun newPrivateKeyCertificatePair(
        commonName: String,
        validUntil: Date,
    ): PrivateKeyCertificatePair {
        logger.fine { "Creating certificate for $commonName" }

        // Generate a new key pair.
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(4096)
        }.generateKeyPair()

        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)

        val name = X500Name("CN=$commonName")
        val certificateHolder = X509v3CertificateBuilder(
            name,
            BigInteger.valueOf(SecureRandom().nextLong()),
            Date(System.currentTimeMillis()),
            validUntil,
            Locale.ENGLISH,
            name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        ).build(contentSigner)
        val certificate = JcaX509CertificateConverter().getCertificate(certificateHolder)

        return PrivateKeyCertificatePair(keyPair.private, certificate)
    }

    /**
     * Read a [PrivateKeyCertificatePair] from a keystore entry.
     *
     * @param keyStore The keystore to read the entry from.
     * @param keyStoreEntryAlias The alias of the key store entry to read.
     * @param keyStoreEntryPassword The password for recovering the signing key.
     *
     * @return The read [PrivateKeyCertificatePair].
     *
     * @throws IllegalArgumentException If the keystore does not contain the given alias or the password is invalid.
     *
     * @see PrivateKeyCertificatePair
     * @see KeyStore
     */
    fun readPrivateKeyCertificatePair(
        keyStore: KeyStore,
        keyStoreEntryAlias: String,
        keyStoreEntryPassword: String,
    ): PrivateKeyCertificatePair {
        logger.fine { "Reading key and certificate pair from keystore entry $keyStoreEntryAlias" }

        if (!keyStore.containsAlias(keyStoreEntryAlias)) {
            throw IllegalArgumentException("Keystore does not contain entry with alias $keyStoreEntryAlias")
        }

        // Read the private key and certificate from the keystore.

        val privateKey =
            try {
                keyStore.getKey(keyStoreEntryAlias, keyStoreEntryPassword.toCharArray()) as PrivateKey
            } catch (exception: UnrecoverableKeyException) {
                throw IllegalArgumentException("Invalid password for keystore entry $keyStoreEntryAlias")
            }

        val certificate = keyStore.getCertificate(keyStoreEntryAlias) as X509Certificate

        return PrivateKeyCertificatePair(privateKey, certificate)
    }

    /**
     * Create a new [Signer].
     *
     * @param signer The name of the signer.
     * @param privateKeyCertificatePair The private key and certificate pair to use for signing.
     *
     * @return The new [Signer].
     *
     * @see PrivateKeyCertificatePair
     * @see Signer
     */
    fun newApkSigner(
        signer: String,
        privateKeyCertificatePair: PrivateKeyCertificatePair,
    ) = Signer(signer, privateKeyCertificatePair)

    /**
     * An entry in a keystore.
     *
     * @param alias The alias of the entry.
     * @param password The password for recovering the signing key.
     * @param privateKeyCertificatePair The private key and certificate pair.
     *
     * @see PrivateKeyCertificatePair
     */
    class KeyStoreEntry(
        val alias: String,
        val password: String,
        val privateKeyCertificatePair: PrivateKeyCertificatePair,
    )

    /**
     * A private key and certificate pair.
     *
     * @param privateKey The private key.
     * @param certificate The certificate.
     */
    class PrivateKeyCertificatePair(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    class Signer internal constructor(
        private val name: String,
        privateKeyCertificatePair: PrivateKeyCertificatePair,
    ) {
        private val keyConfig = KeyConfig.Jca(privateKeyCertificatePair.privateKey)
        private val certificates = listOf(privateKeyCertificatePair.certificate)

        /**
         * Signs [inputApkFile] into [outputApkFile], which may be the same file.
         *
         * An APK every target device verifies with v2 is signed in place with v2 alone:
         * its entries are hashed once and never rewritten. Older targets also need v1,
         * which rewrites every entry into a new file.
         *
         * @param minSdkVersion The lowest API level the APK will be installed on,
         *   if higher than the minimum it declares.
         */
        @JvmOverloads
        fun signApk(inputApkFile: File, outputApkFile: File, minSdkVersion: Int = 0) {
            logger.info("Signing APK")

            val inPlace = inputApkFile.canonicalFile == outputApkFile.canonicalFile
            val lowestSdkVersion = RandomAccessFile(inputApkFile, "r").use { file ->
                ApkSigUtils.getMinSdkVersionFromBinaryAndroidManifest(
                    ApkSigUtils.getAndroidManifest(DataSources.asDataSource(file)),
                )
            }.coerceAtLeast(minSdkVersion)

            if (lowestSdkVersion < V2_ONLY_MIN_SDK_VERSION) {
                val target = if (inPlace) {
                    File.createTempFile("signing", ".apk", outputApkFile.absoluteFile.parentFile)
                } else {
                    outputApkFile
                }
                try {
                    com.android.apksig.ApkSigner.Builder(
                        listOf(com.android.apksig.ApkSigner.SignerConfig.Builder(name, keyConfig, certificates).build()),
                    ).setInputApk(inputApkFile).setOutputApk(target).build().sign()
                    if (inPlace) Files.move(target.toPath(), outputApkFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    if (inPlace) target.delete()
                }
                return
            }

            if (!inPlace) inputApkFile.copyTo(outputApkFile, overwrite = true)
            signInPlace(outputApkFile, lowestSdkVersion)
        }

        private fun signInPlace(apkFile: File, minSdkVersion: Int) = RandomAccessFile(apkFile, "rw").use { file ->
            val apk = DataSources.asDataSource(file)
            val sections = ApkSigUtils.findZipSections(apk)
            val contentsEnd = try {
                ApkSigUtils.findApkSigningBlock(apk, sections).startOffset
            } catch (_: ApkSigningBlockNotFoundException) {
                sections.zipCentralDirectoryOffset
            }
            val centralDirectory = apk.getByteBuffer(
                sections.zipCentralDirectoryOffset,
                sections.zipCentralDirectorySizeBytes.toInt(),
            )
            val endOfCentralDirectory = sections.zipEndOfCentralDirectory

            DefaultApkSignerEngine.Builder(
                listOf(DefaultApkSignerEngine.SignerConfig.Builder(name, keyConfig, certificates).build()),
                minSdkVersion,
            ).setV1SigningEnabled(false).setV3SigningEnabled(false).build().use { engine ->
                val request = engine.outputZipSections2(
                    apk.slice(0, contentsEnd),
                    DataSources.asDataSource(centralDirectory.duplicate()),
                    DataSources.asDataSource(endOfCentralDirectory.duplicate()),
                )
                val padding = ByteBuffer.wrap(ByteArray(request.paddingSizeBeforeApkSigningBlock))
                val block = ByteBuffer.wrap(request.apkSigningBlock)
                request.done()

                val centralDirectoryOffset = contentsEnd + padding.remaining() + block.remaining()
                ApkSigUtils.setZipEocdCentralDirectoryOffset(endOfCentralDirectory, centralDirectoryOffset)

                val channel = file.channel
                var position = contentsEnd
                for (buffer in arrayOf(padding, block, centralDirectory, endOfCentralDirectory)) {
                    while (buffer.hasRemaining()) position += channel.write(buffer, position)
                }
                file.setLength(position)
                engine.outputDone()
            }
        }

        private companion object {
            // Android 7.0, the first release to verify APK Signature Scheme v2
            private const val V2_ONLY_MIN_SDK_VERSION = 24
        }
    }
}
