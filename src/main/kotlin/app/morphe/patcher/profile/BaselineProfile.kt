/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.profile

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A baseline profile: the classes and methods of each DEX file of an APK that ART compiles ahead of
 * time. ART uses the part of a DEX file only while the checksum and the number of ids it records match
 * the DEX file. A profile with one part that matches no DEX file of the APK is not used at all.
 *
 * The ART formats are those of `art/libprofile/profile/profile_compilation_info.cc` in AOSP, and
 * `baseline.prof` with `baseline.profm` are what the Android Gradle plugin writes into an APK.
 */
internal class BaselineProfile(val dexFiles: List<DexProfile>) {
    /** The entries of an APK that carry this profile, as the patched APK gets them. */
    fun toApkEntries(): Map<String, ByteArray> = mapOf(
        PROFILE_ENTRY to writeAsset(),
        METADATA_ENTRY to writeMetadata(),
        EMBEDDED_PROFILE_ENTRY to writeArtProfile(ArtProfileVersion.V015),
    )

    /** The profile as `baseline.prof` holds it, keyed by DEX entry name. */
    fun writeAsset() = writeV010(dexFiles) { it.name }

    /**
     * The profile in the format ART of [version] reads from dex metadata and from the APK itself,
     * keyed as ART names the DEX files of a base APK.
     */
    fun writeArtProfile(version: ArtProfileVersion) = when (version) {
        ArtProfileVersion.V010 -> writeV010(dexFiles, ::artKey)
        ArtProfileVersion.V015 -> writeV015(dexFiles)
    }

    /** `baseline.profm`, which the profile installer of an app needs to write the profile for Android 12 and up. */
    fun writeMetadata(): ByteArray {
        val body = LittleEndianOutput()
        dexFiles.forEachIndexed { index, dex ->
            val key = dex.name.toByteArray(Charsets.UTF_8)
            body.u16(index).u16(key.size).bytes(key).u32(dex.typeCount).u16(dex.classDefs.size)
            body.ascending(dex.classDefs)
        }
        return LittleEndianOutput().bytes(METADATA_MAGIC).bytes(METADATA_V002).u16(dexFiles.size)
            .deflated(body.toByteArray()).toByteArray()
    }

    companion object {
        const val PROFILE_ENTRY = "assets/dexopt/baseline.prof"
        const val METADATA_ENTRY = "assets/dexopt/baseline.profm"

        /** The profile ART service extracts from the APK itself (Android 15 and up), in the ART format. */
        const val EMBEDDED_PROFILE_ENTRY = "assets/art-profile/baseline.prof"

        val ENTRIES = setOf(PROFILE_ENTRY, METADATA_ENTRY, EMBEDDED_PROFILE_ENTRY)

        /**
         * Reads `baseline.prof` as the Android Gradle plugin writes it. The type counts and class
         * definitions it does not record are left unknown, see [DexProfile].
         *
         * @throws IllegalArgumentException If [bytes] are not a profile of this version.
         */
        fun read(bytes: ByteArray): BaselineProfile {
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= V010_HEADER_SIZE) { "The profile is truncated" }
            require(bytes.copyOfRange(0, 4).contentEquals(PROFILE_MAGIC)) { "Not a baseline profile" }
            require(bytes.copyOfRange(4, 8).contentEquals(PROFILE_V010)) { "Unsupported profile version" }
            val count = header.get(8).toInt() and 0xFF
            val inflatedSize = header.getInt(9)
            val deflatedSize = header.getInt(13)
            require(deflatedSize >= 0 && V010_HEADER_SIZE + deflatedSize <= bytes.size) { "The profile is truncated" }

            val body = ByteBuffer.wrap(ProfileCompression.inflate(bytes, V010_HEADER_SIZE, deflatedSize, inflatedSize))
                .order(ByteOrder.LITTLE_ENDIAN)
            class Header(val key: String, val classCount: Int, val hotSize: Int, val checksum: Int, val methodCount: Int)

            val headers = List(count) {
                val keySize = body.u16()
                val classCount = body.u16()
                val hotSize = body.int
                val checksum = body.int
                val methodCount = body.int
                val key = ByteArray(keySize).also { body.get(it) }.toString(Charsets.UTF_8)
                Header(key, classCount, hotSize, checksum, methodCount)
            }
            val dexFiles = headers.map { header ->
                val methods = TreeMap<Int, Int>()
                val hotEnd = body.position() + header.hotSize
                var method = 0
                while (body.position() < hotEnd) {
                    method += body.u16()
                    repeat(body.u16()) { body.skipInlineCache() }
                    methods[method] = HOT
                }
                require(body.position() == hotEnd) { "The hot methods of ${header.key} overrun their region" }

                var type = 0
                val classes = IntArray(header.classCount) { type += body.u16(); type }

                val bitmap = ByteArray((header.methodCount * 2 + 7) / 8).also { body.get(it) }
                for (index in 0 until header.methodCount) {
                    var flags = 0
                    if (bitmap.bit(index)) flags = flags or STARTUP
                    if (bitmap.bit(header.methodCount + index)) flags = flags or POST_STARTUP
                    if (flags != 0) methods.merge(index, flags, Int::or)
                }

                DexProfile(
                    name = dexEntryName(header.key),
                    checksum = header.checksum,
                    typeCount = DexProfile.UNKNOWN,
                    methodCount = header.methodCount,
                    classes = classes,
                    classDefs = IntArray(0),
                    methods = methods.keys.toIntArray(),
                    methodFlags = ByteArray(methods.size).also { flags ->
                        methods.values.forEachIndexed { index, value -> flags[index] = value.toByte() }
                    },
                )
            }
            require(!body.hasRemaining()) { "The profile has data after its DEX files" }
            return BaselineProfile(dexFiles)
        }

        /**
         * The DEX entry a profile key names: `classes.dex` for the base APK itself, otherwise what
         * follows the separator ART and the Android Gradle plugin put between APK and DEX entry.
         */
        private fun dexEntryName(key: String): String {
            val separator = key.lastIndexOfAny(charArrayOf('!', ':'))
            if (separator >= 0) return key.substring(separator + 1)
            return if (key.endsWith(".dex")) key else "classes.dex"
        }

        /** How ART names a DEX entry of the base APK in a profile. */
        private fun artKey(dex: DexProfile) =
            if (dex.name == "classes.dex") "base.apk" else "base.apk!${dex.name}"

        private fun writeV010(dexFiles: List<DexProfile>, key: (DexProfile) -> String): ByteArray {
            require(dexFiles.size <= 0xFF) { "Too many DEX files for a profile of version 010" }
            val body = LittleEndianOutput()
            val keys = dexFiles.map { key(it).toByteArray(Charsets.UTF_8) }
            dexFiles.forEachIndexed { index, dex ->
                body.u16(keys[index].size).u16(dex.classes.size).u32(dex.hotMethodCount() * HOT_METHOD_SIZE)
                    .u32(dex.checksum).u32(dex.methodCount).bytes(keys[index])
            }
            dexFiles.forEach { dex ->
                dex.writeHotMethods(body)
                body.ascending(dex.classes)
                body.bytes(dex.bitmap(listOf(STARTUP, POST_STARTUP)))
            }
            val inflated = body.toByteArray()
            val deflated = ProfileCompression.deflate(inflated)
            return LittleEndianOutput().bytes(PROFILE_MAGIC).bytes(PROFILE_V010).u8(dexFiles.size)
                .u32(inflated.size).u32(deflated.size).bytes(deflated).toByteArray()
        }

        private fun writeV015(dexFiles: List<DexProfile>): ByteArray {
            val dexSection = LittleEndianOutput().u16(dexFiles.size)
            dexFiles.forEach { dex ->
                require(dex.typeCount != DexProfile.UNKNOWN) { "The type count of ${dex.name} is unknown" }
                val key = artKey(dex).toByteArray(Charsets.UTF_8)
                dexSection.u32(dex.checksum).u32(dex.typeCount).u32(dex.methodCount).u16(key.size).bytes(key)
            }

            val classes = LittleEndianOutput()
            val methods = LittleEndianOutput()
            dexFiles.forEachIndexed { index, dex ->
                if (dex.classes.isNotEmpty()) {
                    classes.u16(index).u16(dex.classes.size).ascending(dex.classes)
                }
                val usedFlags = dex.methodFlags.fold(0) { flags, value -> flags or value.toInt() }
                if (usedFlags != 0) {
                    val bitmapFlags = listOf(STARTUP, POST_STARTUP).filter { usedFlags and it != 0 }
                    val data = LittleEndianOutput().u16(usedFlags)
                        .bytes(dex.bitmap(bitmapFlags))
                    dex.writeHotMethods(data)
                    val bytes = data.toByteArray()
                    methods.u16(index).u32(bytes.size).bytes(bytes)
                }
            }

            // The DEX files section is stored, the others deflated, as ART writes them.
            val sections = buildList {
                add(Section(SECTION_DEX_FILES, dexSection.toByteArray(), inflatedSize = 0))
                classes.toByteArray().takeIf { it.isNotEmpty() }?.let { add(Section(SECTION_CLASSES, ProfileCompression.deflate(it), it.size)) }
                methods.toByteArray().takeIf { it.isNotEmpty() }?.let { add(Section(SECTION_METHODS, ProfileCompression.deflate(it), it.size)) }
            }
            val output = LittleEndianOutput().bytes(PROFILE_MAGIC).bytes(PROFILE_V015).u32(sections.size)
            var offset = V015_HEADER_SIZE + sections.size * V015_SECTION_INFO_SIZE
            sections.forEach { section ->
                output.u32(section.type).u32(offset).u32(section.data.size).u32(section.inflatedSize)
                offset += section.data.size
            }
            sections.forEach { output.bytes(it.data) }
            return output.toByteArray()
        }

        private class Section(val type: Int, val data: ByteArray, val inflatedSize: Int)

        /** Skips an inline cache of a hot method, which the Android Gradle plugin does not write. */
        private fun ByteBuffer.skipInlineCache() {
            u16() // Dex pc.
            val size = get().toInt() and 0xFF
            if (size == INLINE_CACHE_MISSING_TYPES || size == INLINE_CACHE_MEGAMORPHIC) return
            repeat(size) {
                get() // Profile index of the DEX file the classes are in.
                val classCount = get().toInt() and 0xFF
                repeat(classCount) { u16() }
            }
        }

        private fun ByteBuffer.u16() = short.toInt() and 0xFFFF

        private fun ByteArray.bit(index: Int) = this[index / 8].toInt() shr (index % 8) and 1 != 0

        private val PROFILE_MAGIC = byteArrayOf('p'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(), 0)
        private val PROFILE_V010 = byteArrayOf('0'.code.toByte(), '1'.code.toByte(), '0'.code.toByte(), 0)
        private val PROFILE_V015 = byteArrayOf('0'.code.toByte(), '1'.code.toByte(), '5'.code.toByte(), 0)
        private val METADATA_MAGIC = byteArrayOf('p'.code.toByte(), 'r'.code.toByte(), 'm'.code.toByte(), 0)
        private val METADATA_V002 = byteArrayOf('0'.code.toByte(), '0'.code.toByte(), '2'.code.toByte(), 0)

        private const val V010_HEADER_SIZE = 17
        private const val V015_HEADER_SIZE = 12
        private const val V015_SECTION_INFO_SIZE = 16
        private const val HOT_METHOD_SIZE = 4

        private const val SECTION_DEX_FILES = 0
        private const val SECTION_CLASSES = 2
        private const val SECTION_METHODS = 3

        private const val INLINE_CACHE_MISSING_TYPES = 6
        private const val INLINE_CACHE_MEGAMORPHIC = 7
    }
}

/** zlib streams, in which every profile format stores its compressed data. */
internal object ProfileCompression {
    fun inflate(bytes: ByteArray, offset: Int, length: Int, inflatedSize: Int): ByteArray {
        require(inflatedSize >= 0) { "The profile is corrupt" }
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, offset, length)
            val output = ByteArray(inflatedSize)
            var size = 0
            while (size < inflatedSize) {
                val count = inflater.inflate(output, size, inflatedSize - size)
                if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                size += count
            }
            require(size == inflatedSize && inflater.finished()) { "The profile is corrupt" }
            return output
        } finally {
            inflater.end()
        }
    }

    // ART and the profile installer inflate a zlib stream, not raw deflate data.
    fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val output = ByteArrayOutputStream(bytes.size / 2 + 64)
            val chunk = ByteArray(16 * 1024)
            while (!deflater.finished()) output.write(chunk, 0, deflater.deflate(chunk))
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }
}

/** The profile formats ART reads from dex metadata and, from Android 15, from the APK. */
internal enum class ArtProfileVersion {
    /** Android 9 to 11. */
    V010,

    /** Android 12 and up. */
    V015;

    companion object {
        /** The version ART of [sdk] reads, or null below Android 9, which reads no profile at install. */
        fun forSdk(sdk: Int) = when {
            sdk < 28 -> null
            sdk < 31 -> V010
            else -> V015
        }
    }
}

/**
 * The part of a [BaselineProfile] for one DEX file.
 *
 * @param name The DEX entry, such as `classes2.dex`.
 * @param checksum The CRC-32 of the DEX entry.
 * @param typeCount The number of type ids of the DEX file, or [UNKNOWN] as `baseline.prof` does not record it.
 * @param methodCount The number of method ids of the DEX file.
 * @param classes The type indices of the classes, ascending.
 * @param classDefs The class definition indices of [classes], ascending, which `baseline.profm` records.
 * @param methods The method indices, ascending.
 * @param methodFlags The flags of [methods], [HOT], [STARTUP] and [POST_STARTUP].
 */
internal class DexProfile(
    val name: String,
    val checksum: Int,
    val typeCount: Int,
    val methodCount: Int,
    val classes: IntArray,
    val classDefs: IntArray,
    val methods: IntArray,
    val methodFlags: ByteArray,
) {
    val isEmpty get() = classes.isEmpty() && methods.isEmpty()

    fun hotMethodCount() = methodFlags.count { it.toInt() and HOT != 0 }

    fun writeHotMethods(output: LittleEndianOutput) {
        var last = 0
        methods.forEachIndexed { index, method ->
            if (methodFlags[index].toInt() and HOT == 0) return@forEachIndexed
            output.u16(method - last).u16(0)
            last = method
        }
    }

    /** One run of [methodCount] bits for each of [flags], in their order. */
    fun bitmap(flags: List<Int>): ByteArray {
        val bitmap = ByteArray((flags.size * methodCount + 7) / 8)
        methods.forEachIndexed { index, method ->
            flags.forEachIndexed { run, flag ->
                if (methodFlags[index].toInt() and flag != 0) {
                    val bit = run * methodCount + method
                    bitmap[bit / 8] = (bitmap[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
                }
            }
        }
        return bitmap
    }

    companion object {
        const val UNKNOWN = -1
    }
}

internal const val HOT = 1
internal const val STARTUP = 1 shl 1
internal const val POST_STARTUP = 1 shl 2

/** Writes little-endian values, as every profile format stores them. */
internal class LittleEndianOutput {
    private val output = ByteArrayOutputStream()

    fun u8(value: Int) = apply { output.write(value) }

    fun u16(value: Int) = apply {
        require(value in 0..0xFFFF) { "$value does not fit 16 bits" }
        output.write(value)
        output.write(value ushr 8)
    }

    fun u32(value: Int) = apply {
        output.write(value)
        output.write(value ushr 8)
        output.write(value ushr 16)
        output.write(value ushr 24)
    }

    fun bytes(bytes: ByteArray) = apply { output.write(bytes) }

    /** Ascending values as the differences between them, the first from 0. */
    fun ascending(values: IntArray) = apply {
        var last = 0
        values.forEach { value ->
            u16(value - last)
            last = value
        }
    }

    /** [data] as the uncompressed size, the compressed size and the compressed data. */
    fun deflated(data: ByteArray) = apply {
        val compressed = ProfileCompression.deflate(data)
        u32(data.size).u32(compressed.size).bytes(compressed)
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}
