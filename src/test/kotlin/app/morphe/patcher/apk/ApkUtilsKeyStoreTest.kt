/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class ApkUtilsKeyStoreTest {
    @TempDir
    lateinit var temporaryDirectory: File

    private fun details(file: File) = ApkUtils.KeyStoreDetails(file, "store-pass", "alias", "key-pass")

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
}
