/*
 * Code hard forked from:
 * https://github.com/revanced/revanced-library/tree/06733072045c8016a75f232dec76505c0ba2e1cd
 */

package app.morphe.patcher.apk

import com.android.apksig.DefaultApkSignerEngine
import com.android.apksig.KeyConfig
import com.android.apksig.apk.ApkSigningBlockNotFoundException
import com.android.apksig.util.DataSources
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.crypto.engines.DESedeEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.bc.BcPKCS12MacCalculatorBuilder
import org.bouncycastle.pkcs.bc.BcPKCS12PBEOutputEncryptorBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

    // As many as keytool uses, which keeps opening a keystore quick on slow devices
    private const val KEY_STORE_ITERATION_COUNT = 10_000

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * The format of a keystore file, told apart by the bytes it begins with.
     */
    enum class KeyStoreType {
        /** The standard format Morphe writes, which every signing tool reads. */
        PKCS12,

        /** BouncyCastle's own format, which Morphe wrote before and still reads. */
        BKS,

        /** The legacy Java format, readable only where the JDK provides it, not on Android. */
        JKS;

        internal fun newInstance(): KeyStore = when (this) {
            JKS -> KeyStore.getInstance(name)
            else -> KeyStore.getInstance(name, BouncyCastleProvider.PROVIDER_NAME)
        }

        companion object {
            /**
             * The type of keystore starting with [header], or null if it matches none.
             *
             * PKCS12 is a DER sequence with a long-form length, JKS begins with its magic
             * number and BKS with its version, a big-endian int of 0, 1 or 2.
             */
            fun of(header: ByteArray): KeyStoreType? {
                if (header.size < 4) return null
                return when {
                    header[0] == 0x30.toByte() && (header[1].toInt() and 0xFF) in 0x80..0x84 -> PKCS12
                    header[0] == 0xFE.toByte() && header[1] == 0xED.toByte() &&
                        header[2] == 0xFE.toByte() && header[3] == 0xED.toByte() -> JKS
                    header[0] == 0.toByte() && header[1] == 0.toByte() && header[2] == 0.toByte() &&
                        header[3] in 0..2 -> BKS
                    else -> null
                }
            }
        }
    }

    /**
     * Write a PKCS12 keystore holding a key for each alias in [entries] to [outputStream].
     *
     * The keys are encrypted with [password] using 3DES, as BouncyCastle, which Android reads
     * PKCS12 with, decrypts them with the keystore password. The certificates are public and left
     * unencrypted, where BouncyCastle's own keystore encrypts them with RC2, which OpenSSL 3 reads
     * only in legacy mode. Only BouncyCastle's own primitives are used, so the file is the same
     * whichever security providers the platform has.
     *
     * @param outputStream The stream to write the keystore to.
     * @param password The password for the keystore and every key in it.
     * @param entries The private key and certificate pairs by alias.
     */
    fun writeKeyStore(outputStream: OutputStream, password: String, entries: Map<String, PrivateKeyCertificatePair>) {
        logger.fine("Writing keystore")

        val passwordChars = password.toCharArray()
        val extensionUtils = JcaX509ExtensionUtils()
        val builder = PKCS12PfxPduBuilder()
        entries.forEach { (alias, pair) ->
            val keyEncryptor = BcPKCS12PBEOutputEncryptorBuilder(
                PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC,
                CBCBlockCipher.newInstance(DESedeEngine()),
            ).setIterationCount(KEY_STORE_ITERATION_COUNT).build(passwordChars)

            // The shared local key id is what pairs a key with its certificate
            val friendlyName = DERBMPString(alias)
            val localKeyId = extensionUtils.createSubjectKeyIdentifier(pair.certificate.publicKey)
            listOf(
                JcaPKCS12SafeBagBuilder(pair.certificate),
                JcaPKCS12SafeBagBuilder(pair.privateKey, keyEncryptor),
            ).forEach {
                builder.addData(
                    it.addBagAttribute(PKCSObjectIdentifiers.pkcs_9_at_friendlyName, friendlyName)
                        .addBagAttribute(PKCSObjectIdentifiers.pkcs_9_at_localKeyId, localKeyId)
                        .build(),
                )
            }
        }

        val macCalculator = BcPKCS12MacCalculatorBuilder().setIterationCount(KEY_STORE_ITERATION_COUNT)
        outputStream.write(builder.build(macCalculator, passwordChars).getEncoded(ASN1Encoding.DER))
    }

    /**
     * Read a keystore of any [KeyStoreType] from the given [keyStoreInputStream].
     *
     * @param keyStoreInputStream The stream to read the keystore from.
     * @param keyStorePassword The password for the keystore.
     *
     * @return The keystore.
     *
     * @throws IllegalArgumentException If the keystore format is not recognized or its password is invalid.
     *
     * @see KeyStore
     */
    fun readKeyStore(
        keyStoreInputStream: InputStream,
        keyStorePassword: String?,
    ): KeyStore {
        val data = keyStoreInputStream.readBytes()
        return readKeyStore(data, keyStoreType(data), keyStorePassword)
    }

    internal fun keyStoreType(data: ByteArray) =
        requireNotNull(KeyStoreType.of(data)) { "Unrecognized keystore format" }

    internal fun readKeyStore(data: ByteArray, type: KeyStoreType, keyStorePassword: String?): KeyStore {
        logger.fine { "Reading $type keystore" }

        return type.newInstance().apply {
            try {
                load(data.inputStream(), keyStorePassword?.toCharArray())
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

        require(keyStore.containsAlias(keyStoreEntryAlias)) {
            "Keystore does not contain entry with alias $keyStoreEntryAlias"
        }

        // Read the private key and certificate from the keystore.

        val privateKey =
            try {
                keyStore.getKey(keyStoreEntryAlias, keyStoreEntryPassword.toCharArray()) as PrivateKey
            } catch (_: UnrecoverableKeyException) {
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

            if (!inPlace) ApkUtils.copyApk(inputApkFile, outputApkFile)
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
