/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import com.reandroid.archive.block.SignatureId

/**
 * An APK signature scheme, identified by the ID of its block within the APK Signing Block.
 *
 * Well-known schemes are provided as constants ([V2], [V3], [V31]). Any other scheme, including
 * ones not recognized by this class (such as source stamps, padding, or future schemes), is
 * represented by an instance carrying that raw [id], so arbitrary schemes can still be
 * distinguished and compared.
 *
 * @property id The ID of the signature scheme's block within the APK Signing Block.
 */
data class ApkSignatureScheme(val id: Int) {
    companion object {
        /** APK Signature Scheme v2. */
        val V2 = ApkSignatureScheme(SignatureId.V2.id)

        /** APK Signature Scheme v3. */
        val V3 = ApkSignatureScheme(SignatureId.V3.id)

        /** APK Signature Scheme v3.1. */
        val V31 = ApkSignatureScheme(SignatureId.V31.id)

        /**
         * Create an [ApkSignatureScheme] from an ARSCLib [SignatureId].
         *
         * @param signatureId The signature ID whose block ID is used.
         * @return An [ApkSignatureScheme] whose [id] is the block ID of [signatureId].
         */
        internal fun fromSignatureId(signatureId: SignatureId): ApkSignatureScheme = ApkSignatureScheme(signatureId.id)
    }
}
