/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import com.reandroid.archive.block.SignatureId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

internal class ApkSignatureSchemeTest {

    @Test
    fun `well-known schemes expose the expected APK Signing Block ids`() {
        assertEquals(0x7109871A, ApkSignatureScheme.V2.id)
        assertEquals(0xF05368C0.toInt(), ApkSignatureScheme.V3.id)
        assertEquals(0x1B93AD61, ApkSignatureScheme.V31.id)
    }

    @Test
    fun `well-known schemes match their SignatureId`() {
        assertEquals(SignatureId.V2.id, ApkSignatureScheme.V2.id)
        assertEquals(SignatureId.V3.id, ApkSignatureScheme.V3.id)
        assertEquals(SignatureId.V31.id, ApkSignatureScheme.V31.id)
    }

    @Test
    fun `well-known schemes are distinct from one another`() {
        assertNotEquals(ApkSignatureScheme.V2, ApkSignatureScheme.V3)
        assertNotEquals(ApkSignatureScheme.V3, ApkSignatureScheme.V31)
        assertNotEquals(ApkSignatureScheme.V2, ApkSignatureScheme.V31)
    }

    @Test
    fun `fromSignatureId maps known ids to their constants`() {
        assertEquals(ApkSignatureScheme.V2, ApkSignatureScheme.fromSignatureId(SignatureId.V2))
        assertEquals(ApkSignatureScheme.V3, ApkSignatureScheme.fromSignatureId(SignatureId.V3))
        assertEquals(ApkSignatureScheme.V31, ApkSignatureScheme.fromSignatureId(SignatureId.V31))
    }

    @Test
    fun `fromSignatureId preserves arbitrary ids`() {
        val unknownId = 0x12345678
        val scheme = ApkSignatureScheme.fromSignatureId(SignatureId.valueOf(unknownId))

        assertEquals(ApkSignatureScheme(unknownId), scheme)
        assertEquals(unknownId, scheme.id)
    }

    @Test
    fun `an arbitrary scheme does not equal a well-known scheme`() {
        assertNotEquals(ApkSignatureScheme.V2, ApkSignatureScheme(0x12345678))
    }

    @Test
    fun `schemes with the same id are equal`() {
        assertEquals(ApkSignatureScheme.V2, ApkSignatureScheme(SignatureId.V2.id))
    }
}
