/*
 * Code hard forked from:
 * https://github.com/revanced/revanced-library/tree/06733072045c8016a75f232dec76505c0ba2e1cd
 */

package app.morphe.patcher.apk

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
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
    ) = Signer(
        com.android.apksig.ApkSigner.Builder(
            listOf(
                com.android.apksig.ApkSigner.SignerConfig.Builder(
                    signer,
                    privateKeyCertificatePair.privateKey,
                    listOf(privateKeyCertificatePair.certificate),
                ).build(),
            ),
        ),
    )

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

    class Signer internal constructor(private val signerBuilder: com.android.apksig.ApkSigner.Builder) {
        fun signApk(inputApkFile: File, outputApkFile: File) {
            logger.info("Signing APK")

            signerBuilder.setInputApk(inputApkFile)?.setOutputApk(outputApkFile)?.build()?.sign()
        }
    }
}
