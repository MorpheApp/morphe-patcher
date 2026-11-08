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
import java.io.File
import java.security.cert.X509Certificate
import java.util.Collections

/**
 * Metadata about a package.
 *
 * @param packageName The name of the package.
 * @param versionCode The version code of the package.
 * @param versionName The version name of the package.
 * @param signatureBlock The APK signature block of the package, or `null` if the package is unsigned.
 * @param obbFiles The OBB files of the bundle the package was merged from, keyed by the path they have
 * inside the bundle, such as `Android/obb/com.example/main.1.com.example.obb`. Only OBB files are included,
 * never other files of the bundle. The files belong to the caller of the patcher and are read-only to
 * patches: a patch that needs a changed copy writes it to [app.morphe.patcher.patch.fileWorkspace].
 * A patch that reads one should declare [app.morphe.patcher.patch.ApkFileType.XAPK_REQUIRED], and still
 * expect an empty map, since the map is also empty when the package was patched from a plain APK, the
 * bundle has no OBB, or the caller of the patcher did not extract them.
 */
class PackageMetadata internal constructor(
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    private val signatureBlock: ApkSignatureBlock?,
    val obbFiles: Map<String, File> = emptyMap(),
) {
    /**
     * A copy of this metadata with [obbFiles] set to a read-only copy of [files], so neither the caller
     * of the patcher nor a patch can change the map the other one sees.
     */
    internal fun withObbFiles(files: Map<String, File>) = PackageMetadata(
        packageName,
        versionName,
        versionCode,
        signatureBlock,
        Collections.unmodifiableMap(LinkedHashMap(files)),
    )

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
