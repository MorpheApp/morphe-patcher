/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

/**
 * Signs patch bundles and verifies the signature they carry in the comment of their zip archive.
 *
 * A signed bundle ends with the comment `MorpheSig1 <public key> <signature>`, both Base64.
 * The public key is a raw Ed25519 key. The signature is an Ed25519 signature of the bytes of
 * `MorpheSig1` followed by the SHA-256 digest of the bundle as it was before it was signed,
 * which is the whole archive with an empty comment. Every byte of the bundle is covered, and
 * readers of the archive ignore the comment, so a signed bundle loads like an unsigned one.
 *
 * The patches Gradle plugin signs bundles the same way.
 */
@Suppress("unused")
object PatchBundleSignature {
    private const val MAGIC = "MorpheSig1"

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_SIZE = 22
    private const val MAX_COMMENT_SIZE = 0xFFFF

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    /**
     * Signs [file] with [privateKey], a Base64 raw Ed25519 private key.
     *
     * @return The Base64 public key of [privateKey].
     * @throws IllegalArgumentException If [file] is not a zip archive without a comment,
     * or [privateKey] is not an Ed25519 key.
     */
    fun sign(file: File, privateKey: String): String = RandomAccessFile(file, "rw").use { archive ->
        val key = Ed25519PrivateKeyParameters(decoder.decode(privateKey.trim()))
        val publicKey = encoder.encodeToString(key.generatePublicKey().encoded)

        require(commentOffset(archive) == archive.length()) { "$file is already signed or not a zip archive" }

        val signature = Ed25519Signer().run {
            init(true, key)
            val message = message(archive, archive.length())
            update(message, 0, message.size)
            generateSignature()
        }

        val comment = "$MAGIC $publicKey ${encoder.encodeToString(signature)}".toByteArray(Charsets.US_ASCII)
        archive.seek(archive.length() - 2)
        archive.write(comment.size and 0xFF)
        archive.write(comment.size shr 8)
        archive.write(comment)

        publicKey
    }

    /**
     * Verifies the signature of [file].
     *
     * @return The Base64 public key of the signer, or null if [file] is not signed.
     * @throws SecurityException If [file] is signed, but the signature does not match its content.
     */
    fun verify(file: File): String? = RandomAccessFile(file, "r").use { archive ->
        val commentOffset = commentOffset(archive) ?: return null
        val comment = ByteArray((archive.length() - commentOffset).toInt())
            .also { archive.seek(commentOffset); archive.readFully(it) }
            .toString(Charsets.US_ASCII)
        if (!comment.startsWith("$MAGIC ")) return null

        val parts = comment.split(' ')
        val (publicKey, signature) = try {
            require(parts.size == 3)
            Ed25519PublicKeyParameters(decoder.decode(parts[1])) to decoder.decode(parts[2])
        } catch (_: IllegalArgumentException) {
            throw SecurityException("The signature of the patch bundle $file is malformed")
        }

        val verified = Ed25519Signer().run {
            init(false, publicKey)
            val message = message(archive, commentOffset)
            update(message, 0, message.size)
            verifySignature(signature)
        }
        if (!verified) throw SecurityException("The signature of the patch bundle $file does not match its content")

        parts[1]
    }

    /**
     * A short form of [publicKey] for people to compare, the first 8 bytes of its SHA-256 digest
     * as hex in groups of four, such as `3931 c916 5ee4 266a`.
     */
    fun fingerprint(publicKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(decoder.decode(publicKey))
            .take(8)
            .joinToString("") { "%02x".format(it) }
            .chunked(4)
            .joinToString(" ")

    /**
     * What is signed for [archive] whose comment starts at [commentOffset]: the archive up to its
     * comment, with the comment length that precedes it zeroed as it was before signing.
     */
    private fun message(archive: RandomAccessFile, commentOffset: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        archive.seek(0)
        var remaining = commentOffset - 2
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = archive.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            digest.update(buffer, 0, read)
            remaining -= read
        }
        digest.update(ByteArray(2))

        return MAGIC.toByteArray(Charsets.US_ASCII) + digest.digest()
    }

    /**
     * Where the comment of the zip archive starts, or null if [archive] is not a zip archive.
     */
    private fun commentOffset(archive: RandomAccessFile): Long? {
        val length = archive.length()
        if (length < EOCD_SIZE) return null

        val tailSize = minOf(length, (EOCD_SIZE + MAX_COMMENT_SIZE).toLong()).toInt()
        val tail = ByteArray(tailSize).also { archive.seek(length - tailSize); archive.readFully(it) }

        fun int(at: Int, bytes: Int) = (0 until bytes).fold(0) { value, i ->
            value or ((tail[at + i].toInt() and 0xFF) shl (8 * i))
        }

        // The end of central directory record is the last one whose comment reaches the end
        for (start in tailSize - EOCD_SIZE downTo 0) {
            if (int(start, 4) == EOCD_SIGNATURE && int(start + EOCD_SIZE - 2, 2) == tailSize - start - EOCD_SIZE) {
                return length - tailSize + start + EOCD_SIZE
            }
        }
        return null
    }
}
