/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.apk.ApkSignatureScheme
import com.reandroid.archive.block.ApkSignatureBlock
import com.reandroid.archive.block.CertificateBlock
import com.reandroid.archive.block.SignatureId
import com.reandroid.archive.block.SignatureInfo
import io.mockk.every
import io.mockk.mockk
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PackageMetadataTest {

    @Test
    fun `signingCertificates returns an empty map when the package is unsigned`() {
        val metadata = packageMetadata(signatureBlock = null)

        assertTrue(metadata.signingCertificates.isEmpty())
    }

    @Test
    fun `signingCertificates groups certificates by signature scheme`() {
        val certificate = mockk<X509Certificate>()
        val metadata = packageMetadata(
            signatureBlock(
                signatureInfo(SignatureId.V2, certBlock(certificate)),
                signatureInfo(SignatureId.V3, certBlock(certificate)),
            ),
        )

        assertEquals(
            mapOf(
                ApkSignatureScheme.V2 to listOf(certificate),
                ApkSignatureScheme.V3 to listOf(certificate),
            ),
            metadata.signingCertificates,
        )
    }

    @Test
    fun `signingCertificates keeps every certificate of a scheme in order`() {
        val first = mockk<X509Certificate>()
        val second = mockk<X509Certificate>()
        val metadata = packageMetadata(
            signatureBlock(
                signatureInfo(SignatureId.V3, certBlock(first), certBlock(second)),
            ),
        )

        assertEquals(listOf(first, second), metadata.signingCertificates[ApkSignatureScheme.V3])
    }

    @Test
    fun `signingCertificates skips certificates that fail to parse`() {
        val certificate = mockk<X509Certificate>()
        val metadata = packageMetadata(
            signatureBlock(
                signatureInfo(SignatureId.V2, certBlock(null), certBlock(certificate), certBlock(null)),
            ),
        )

        assertEquals(listOf(certificate), metadata.signingCertificates[ApkSignatureScheme.V2])
    }

    @Test
    fun `signingCertificates maps a scheme without parseable certificates to an empty list`() {
        val metadata = packageMetadata(
            signatureBlock(
                signatureInfo(SignatureId.V2, certBlock(null)),
            ),
        )

        assertEquals(
            mapOf(ApkSignatureScheme.V2 to emptyList<X509Certificate>()),
            metadata.signingCertificates,
        )
    }

    @Test
    fun `signingCertificates maps a non-certificate scheme to an empty list`() {
        val metadata = packageMetadata(
            signatureBlock(
                signatureInfo(SignatureId.PADDING),
            ),
        )

        assertEquals(
            mapOf(ApkSignatureScheme(SignatureId.PADDING.id) to emptyList<X509Certificate>()),
            metadata.signingCertificates,
        )
    }

    @Test
    fun `signingCertificates preserves an unrecognized signature scheme id`() {
        val unknownId = 0x12345678
        val certificate = mockk<X509Certificate>()
        val metadata = packageMetadata(
            signatureBlock(
                signatureInfo(SignatureId.valueOf(unknownId), certBlock(certificate)),
            ),
        )

        assertEquals(
            mapOf(ApkSignatureScheme(unknownId) to listOf(certificate)),
            metadata.signingCertificates,
        )
    }

    private fun packageMetadata(signatureBlock: ApkSignatureBlock?) =
        PackageMetadata("com.test.app", "1.0.0", "1", signatureBlock)

    private fun certBlock(certificate: X509Certificate?): CertificateBlock {
        val block = mockk<CertificateBlock>()
        every { block.certificate } returns certificate
        return block
    }

    private fun signatureInfo(signatureId: SignatureId, vararg certificates: CertificateBlock): SignatureInfo {
        val info = mockk<SignatureInfo>()
        every { info.id } returns signatureId
        every { info.certificates } answers { certificates.toMutableList().iterator() }
        return info
    }

    private fun signatureBlock(vararg signatures: SignatureInfo): ApkSignatureBlock {
        val block = mockk<ApkSignatureBlock>()
        every { block.iterator() } answers { signatures.toMutableList().iterator() }
        return block
    }
}
