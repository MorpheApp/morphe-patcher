/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import com.android.apksig.apk.ApkFormatException
import com.android.apksig.apk.ApkSigningBlockNotFoundException
import com.android.apksig.util.DataSource
import com.android.apksig.util.DataSources
import com.android.apksig.zip.ZipFormatException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.android.apksig.apk.ApkUtils as ApkSigUtils

/**
 * Reads and writes the ID-value pairs of an APK Signing Block, the block between the entries of an APK
 * and its central directory that holds the v2 and v3 signatures.
 *
 * The block is its size, its pairs, its size again and a magic. Each pair is its size, a 4-byte ID and a
 * value, all little-endian. The v2 and v3 digests cover the entries, the central directory and the end of
 * central directory but not the block, so a pair can be replaced without breaking either signature.
 *
 * ARSCLib's `ApkSignatureBlock` (which [app.morphe.patcher.PackageMetadata] reads) also models the block, but
 * this builds it with the same page padding as apksig's own signer, so a block rebuilt here is byte-identical
 * to the one the signer wrote.
 */
internal object ApkSigningBlock {
    /**
     * An ID-value pair of an APK Signing Block.
     */
    class Entry(val id: Int, val value: ByteArray)

    /**
     * The ID of the pair apksig pads the block with, so that the block fills whole pages.
     */
    const val PADDING_ID = 0x42726577

    /**
     * The ID of the pair through which the patcher hands the v2 signature of the input APK to the signer, see
     * [app.morphe.patcher.patch.retainOriginalV2Signature]. The signer moves the value to the v2 slot, so no
     * signed APK holds this pair. It is "MRPH" read as a little-endian int.
     */
    const val RETAINED_V2_SIGNATURE_ID = 0x4850524D

    private const val PAGE_SIZE = 4096

    // The size of a pair and its ID, which a pair has even when its value is empty.
    private const val PAIR_HEADER_SIZE = 12
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    /**
     * The pairs of the APK Signing Block of [apk] in their order, or none if it has no such block.
     *
     * @throws ApkFormatException If [apk] is not a ZIP archive or a pair of its block is malformed.
     */
    fun entriesOf(apk: DataSource): List<Entry> {
        val block = try {
            ApkSigUtils.findApkSigningBlock(apk, ApkSigUtils.findZipSections(apk))
        } catch (_: ApkSigningBlockNotFoundException) {
            return emptyList()
        } catch (e: ZipFormatException) {
            // So callers that handle a malformed APK by its ApkFormatException, as apksig throws for one, still do.
            throw ApkFormatException("Not a valid ZIP archive", e)
        }
        return parse(block.contents.getByteBuffer(0, block.contents.size().toInt()))
    }

    /**
     * The value of the first pair with [id] in the APK Signing Block of [apkFile], or null if it has none.
     *
     * @throws ApkFormatException If [apkFile] is not a ZIP archive or a pair of its block is malformed.
     */
    fun valueOf(apkFile: File, id: Int): ByteArray? = RandomAccessFile(apkFile, "r").use { file ->
        entriesOf(DataSources.asDataSource(file)).firstOrNull { it.id == id }?.value
    }

    /**
     * The pairs of [block], a whole APK Signing Block.
     *
     * @throws ApkFormatException If a pair is truncated or runs past the end of the pairs.
     */
    fun parse(block: ByteBuffer): List<Entry> {
        val buffer = block.slice().order(ByteOrder.LITTLE_ENDIAN)
        // The second size and the magic follow the pairs.
        val pairsEnd = buffer.limit() - Long.SIZE_BYTES - MAGIC.size
        buffer.position(Long.SIZE_BYTES)
        return buildList {
            while (buffer.position() < pairsEnd) {
                if (pairsEnd - buffer.position() < PAIR_HEADER_SIZE) {
                    throw ApkFormatException("Truncated pair in the APK Signing Block")
                }
                val size = buffer.long
                if (size < Int.SIZE_BYTES || size > pairsEnd - buffer.position()) {
                    throw ApkFormatException("Pair of invalid size $size in the APK Signing Block")
                }
                val id = buffer.int
                add(Entry(id, ByteArray((size - Int.SIZE_BYTES).toInt()).also { buffer.get(it) }))
            }
        }
    }

    /**
     * An APK Signing Block holding [entries] in their order, padded at the end to fill whole pages as apksig
     * pads it. Padding pairs in [entries] are left out, as the block gets a padding pair of its own size.
     */
    fun build(entries: List<Entry>): ByteArray {
        val pairs = entries.filter { it.id != PADDING_ID }
        val unpaddedSize = Long.SIZE_BYTES + pairs.sumOf { PAIR_HEADER_SIZE + it.value.size } +
            Long.SIZE_BYTES + MAGIC.size
        // A padding pair is at least its size and ID long, so a shorter gap is widened by a page.
        val paddingSize = ((PAGE_SIZE - unpaddedSize % PAGE_SIZE) % PAGE_SIZE).let { gap ->
            if (gap in 1 until PAIR_HEADER_SIZE) gap + PAGE_SIZE else gap
        }
        val size = unpaddedSize + paddingSize

        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(size - Long.SIZE_BYTES.toLong())
            pairs.forEach { pair -> putPair(pair.id, pair.value.size) { put(pair.value) } }
            if (paddingSize > 0) putPair(PADDING_ID, paddingSize - PAIR_HEADER_SIZE) {
                position(position() + paddingSize - PAIR_HEADER_SIZE)
            }
            putLong(size - Long.SIZE_BYTES.toLong())
            put(MAGIC)
        }.array()
    }

    private inline fun ByteBuffer.putPair(id: Int, valueSize: Int, putValue: ByteBuffer.() -> Unit) {
        putLong(Int.SIZE_BYTES + valueSize.toLong())
        putInt(id)
        putValue()
    }

    /**
     * [block], an APK Signing Block, with [signature] as its v2 signature, ahead of its other pairs and in
     * place of any v2 signature it has.
     */
    fun withV2Signature(block: ByteArray, signature: ByteArray): ByteArray {
        val others = parse(ByteBuffer.wrap(block)).filter { it.id != ApkSignatureScheme.V2.id }
        return build(listOf(Entry(ApkSignatureScheme.V2.id, signature)) + others)
    }

    /**
     * Hands [signature], the v2 signature of the input APK, to the signer in the APK Signing Block of
     * [apkFile], an unsigned patched APK, see [RETAINED_V2_SIGNATURE_ID].
     */
    fun writeRetainedV2Signature(apkFile: File, signature: ByteArray) =
        write(apkFile, listOf(Entry(RETAINED_V2_SIGNATURE_ID, signature)))

    /**
     * Writes an APK Signing Block holding [entries] to [apkFile], in place of the one it has, if any. The
     * entries of the archive stay where they are, and the end of central directory points past the block.
     */
    fun write(apkFile: File, entries: List<Entry>) = RandomAccessFile(apkFile, "rw").use { file ->
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
        val block = ByteBuffer.wrap(build(entries))
        ApkSigUtils.setZipEocdCentralDirectoryOffset(endOfCentralDirectory, contentsEnd + block.remaining())

        val channel = file.channel
        var position = contentsEnd
        for (buffer in arrayOf(block, centralDirectory, endOfCentralDirectory)) {
            while (buffer.hasRemaining()) position += channel.write(buffer, position)
        }
        file.setLength(position)
    }
}
