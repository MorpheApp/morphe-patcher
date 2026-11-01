/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pkcs.PKCS12PfxPdu
import org.bouncycastle.pkcs.PKCS12SafeBagFactory
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class ApkUtilsKeyStoreTest {
    @TempDir
    lateinit var temporaryDirectory: File

    private fun details(file: File) = ApkUtils.KeyStoreDetails(file, "store-pass", "alias", KEY_PASSWORD)

    @Test
    fun `new keystore is written complete and leaves no staging file behind`() {
        val keyStoreFile = temporaryDirectory.resolve("test.keystore")
        val details = details(keyStoreFile)

        ApkUtils.newPrivateKeyCertificatePair(ApkUtils.PrivateKeyCertificatePairDetails(), details)

        assertTrue(keyStoreFile.length() > 0)
        assertFalse(temporaryDirectory.resolve("test.keystore.tmp").exists())
        assertNotNull(ApkUtils.readPrivateKeyCertificatePairFromKeyStore(details))
    }

    @Test
    fun `existing keystore is replaced as a whole`() {
        val keyStoreFile = temporaryDirectory.resolve("test.keystore").apply { writeText("old") }
        val details = details(keyStoreFile)

        ApkUtils.newPrivateKeyCertificatePair(ApkUtils.PrivateKeyCertificatePairDetails(), details)

        assertEquals(listOf("test.keystore"), temporaryDirectory.list()!!.toList())
        assertNotNull(ApkUtils.readPrivateKeyCertificatePairFromKeyStore(details))
    }

    @Test
    fun `new keystore is PKCS12 that the JDK opens with the key password`() {
        val keyStoreFile = temporaryDirectory.resolve("test.keystore")
        val details = ApkUtils.KeyStoreDetails(keyStoreFile, alias = "alias", password = KEY_PASSWORD)

        ApkUtils.newPrivateKeyCertificatePair(ApkUtils.PrivateKeyCertificatePairDetails(), details)

        assertEquals(ApkSigner.KeyStoreType.PKCS12, ApkSigner.KeyStoreType.of(keyStoreFile.readBytes()))
        assertNotNull(jdkPkcs12(keyStoreFile.readBytes()).getKey("alias", KEY_PASSWORD.toCharArray()))
    }

    @Test
    fun `BKS keystore without a store password is still read`() {
        val keyStoreFile = temporaryDirectory.resolve("legacy.keystore")
        val pair = ApkSigner.newPrivateKeyCertificatePair("Legacy", ApkUtils.PrivateKeyCertificatePairDetails().validUntil)
        keyStoreFile.outputStream().use { stream ->
            KeyStore.getInstance("BKS", BouncyCastleProvider.PROVIDER_NAME).apply {
                load(null)
                setKeyEntry("alias", pair.privateKey, KEY_PASSWORD.toCharArray(), arrayOf(pair.certificate))
            }.store(stream, null)
        }

        val details = ApkUtils.KeyStoreDetails(keyStoreFile, alias = "alias", password = KEY_PASSWORD)

        assertEquals(ApkSigner.KeyStoreType.BKS, ApkSigner.KeyStoreType.of(keyStoreFile.readBytes()))
        assertEquals(pair.certificate, ApkUtils.readPrivateKeyCertificatePairFromKeyStore(details).certificate)
    }

    @Test
    fun `exported keystore is PKCS12 with the same key under the key password`() {
        val details = details(temporaryDirectory.resolve("test.keystore"))
        val pair = ApkUtils.newPrivateKeyCertificatePair(ApkUtils.PrivateKeyCertificatePairDetails(), details)

        val exported = ByteArrayOutputStream().also { ApkUtils.exportKeyStore(details, it) }.toByteArray()
        val keyStore = jdkPkcs12(exported)

        assertEquals(pair.certificate, keyStore.getCertificate("alias"))
        assertEquals(pair.privateKey, keyStore.getKey("alias", KEY_PASSWORD.toCharArray()) as PrivateKey)
    }

    @Test
    fun `written keystore leaves certificates unencrypted and encrypts keys with 3DES`() {
        val details = details(temporaryDirectory.resolve("test.keystore"))
        ApkUtils.newPrivateKeyCertificatePair(ApkUtils.PrivateKeyCertificatePairDetails(), details)

        val exported = ByteArrayOutputStream().also { ApkUtils.exportKeyStore(details, it) }.toByteArray()
        val pdu = PKCS12PfxPdu(exported)

        // RC2 encrypted data is what OpenSSL 3 refuses outside legacy mode
        assertTrue(pdu.contentInfos.all { it.contentType == PKCSObjectIdentifiers.data })
        val keyBag = pdu.contentInfos.flatMap { PKCS12SafeBagFactory(it).safeBags.toList() }
            .single { it.type == PKCSObjectIdentifiers.pkcs8ShroudedKeyBag }
        assertEquals(
            PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC,
            (keyBag.bagValue as PKCS8EncryptedPrivateKeyInfo).encryptionAlgorithm.algorithm,
        )
    }

    // The JDK's own provider stands in for the signing tools that never load BouncyCastle
    private fun jdkPkcs12(data: ByteArray) =
        KeyStore.getInstance("PKCS12").apply { load(data.inputStream(), KEY_PASSWORD.toCharArray()) }

    private companion object {
        const val KEY_PASSWORD = "key-pass"
    }
}
