/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.apk.ApkSignatureScheme
import com.reandroid.archive.block.ApkSignatureBlock
import java.security.cert.X509Certificate

/**
 * Metadata about a package.
 *
 * @param packageName The name of the package.
 * @param versionCode The version code of the package.
 * @param versionName The version name of the package.
 * @param signatureBlock The APK signature block of the package, or `null` if the package is unsigned.
 */
class PackageMetadata internal constructor(
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    private val signatureBlock: ApkSignatureBlock?
) {
    /**
     * Get the signing certificates of the package, grouped by the [ApkSignatureScheme] they belong to.
     *
     * A single APK signing block can hold multiple signature schemes (for example,
     * [ApkSignatureScheme.V2] and [ApkSignatureScheme.V3]), in which case the same signer
     * certificate is usually present under each scheme that was used to sign the package.
     *
     * Only certificates that can be parsed are included. Schemes that do not carry any parseable
     * certificate, such as padding, source stamp, or unrecognized blocks, map to an empty list.
     * If the package is unsigned, an empty map is returned.
     *
     * @return A map of each [ApkSignatureScheme] present in the signing block to its [X509Certificate]s.
     */
    val signingCertificates: Map<ApkSignatureScheme, List<X509Certificate>> by lazy {
        buildMap {
            signatureBlock?.forEach { signatureInfo ->
                put(
                    ApkSignatureScheme.fromSignatureId(signatureInfo.id),
                    buildList {
                        signatureInfo.certificates.forEach { certificateBlock ->
                            certificateBlock.certificate?.also{ add(it) }
                        }
                    }
                )
            }
        }
    }
}
