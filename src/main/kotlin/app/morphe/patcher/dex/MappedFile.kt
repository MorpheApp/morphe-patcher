/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import app.morphe.patcher.environment.EnvironmentUtils
import sun.misc.Unsafe
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * A writable, whole-file memory mapping that can be deterministically released.
 *
 * On JDK 22+, this is backed by the Foreign Function & Memory (FFM) API and an
 * [java.lang.foreign.Arena]. Closing the mapping closes the arena, which
 * deterministically unmaps the region. This matters on Windows, where an active
 * mapping holds a lock that prevents the underlying file from being deleted,
 * renamed, or rewritten until every mapping over it is released.
 *
 * On platforms without the FFM API (e.g. Android, or JDK < 22), it falls back to
 * [RandomAccessFile]. Closing releases the file handle, but as before the mapping
 * itself is only unmapped once the garbage collector reclaims the buffer.
 *
 * Implementations are backed by a shared (multi-thread accessible) mapping, so the
 * [buffer] may safely be read from threads other than the one that created it.
 */
internal interface MappedFile : Closeable {
    /**
     * The original File backing this MappedFile.
     */
    val originalFile: File

    /**
     * The mapped, writable [ByteBuffer] covering the whole file.
     *
     * Once [close] has been called the buffer must no longer be used; with the
     * FFM-backed implementation accessing it afterwards throws.
     */
    val buffer: ByteBuffer

    /**
     * Forces any changes made to [buffer] to be written back to storage.
     */
    fun force()

    companion object {
        /**
         * Maps [file] into memory for reading and writing.
         *
         * Uses the FFM API when available (see [Ffm.isSupported]) and otherwise
         * falls back to [RandomAccessFile].
         */
        internal fun mapReadWrite(file: File): MappedFile =
            if (Ffm.isSupported) FfmMappedFile(file) else RandomAccessMappedFile(file)

        /**
         * Whether [mapReadWrite] uses the FFM API (`true`) or the [RandomAccessFile]
         * fallback (`false`) on the current runtime. Exposed for tests.
         */
        internal val isFfmSupported: Boolean
            get() = Ffm.isSupported
    }
}

/**
 * FFM-backed [MappedFile]. All FFM types are accessed reflectively so this class
 * can be loaded on runtimes without the FFM API; [MappedFile.mapReadWrite] only
 * instantiates it when [Ffm.isSupported] is `true`.
 */
private class FfmMappedFile(override val originalFile: File) : MappedFile {
    private val channel = FileChannel.open(originalFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
    private val arena: AutoCloseable
    private val segment: Any
    override val buffer: ByteBuffer
    private var closed = false

    init {
        var arenaToClose: AutoCloseable? = null
        try {
            val sharedArena = Ffm.newSharedArena()
            arenaToClose = sharedArena
            segment = Ffm.map(channel, sharedArena, channel.size())
            buffer = Ffm.asByteBuffer(segment)
            arena = sharedArena
        } catch (throwable: Throwable) {
            arenaToClose?.close()
            channel.close()
            throw throwable
        }
    }

    override fun force() {
        Ffm.force(segment)
    }

    override fun close() {
        if (closed) return
        closed = true
        channel.use { _ ->
            arena.close()
        }
    }
}

/**
 * [RandomAccessFile]-backed [MappedFile] used when the FFM API is unavailable.
 * Preserves the historic behaviour where the mapping is unmapped lazily by the
 * garbage collector once the buffer becomes unreachable.
 */
private class RandomAccessMappedFile(override val originalFile: File) : MappedFile {
    private val randomAccessFile = RandomAccessFile(originalFile, "rw")
    private val mappedBuffer = randomAccessFile.channel.map(
        FileChannel.MapMode.READ_WRITE,
        0,
        randomAccessFile.length(),
    )
    private var closed = false

    override val buffer: ByteBuffer
        get() = mappedBuffer

    override fun force() {
        mappedBuffer.force()
    }

    override fun close() {
        if (closed) return
        closed = true

        if (EnvironmentUtils.isWindowsEnvironment) unsafeUnmap(mappedBuffer)
        randomAccessFile.close()
    }

    companion object {
        fun unsafeUnmap(buffer: MappedByteBuffer) {
            try {
                val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
                unsafeField.isAccessible = true
                val unsafe = unsafeField.get(null) as Unsafe
                unsafe.invokeCleaner(buffer)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

/**
 * Reflective bridge to the JDK 22+ Foreign Function & Memory API.
 *
 * The FFM types are resolved lazily and only when running on a JDK that finalized
 * the API (feature version 22 or newer). This avoids referencing
 * `java.lang.foreign` types directly, which keeps the class loadable on Android
 * and older JDKs that don't ship the API.
 */
private object Ffm {
    private val ofSharedMethod: Method?
    private val mapMethod: Method?
    private val asByteBufferMethod: Method?
    private val forceMethod: Method?

    /**
     * Whether the FFM API is available and usable on the current runtime.
     */
    val isSupported: Boolean

    init {
        var ofShared: Method? = null
        var map: Method? = null
        var asByteBuffer: Method? = null
        var force: Method? = null
        var supported = false

        try {
            val arenaClass = Class.forName("java.lang.foreign.Arena")
            val memorySegmentClass = Class.forName("java.lang.foreign.MemorySegment")

            ofShared = arenaClass.getMethod("ofShared")
            map = FileChannel::class.java.getMethod(
                "map",
                FileChannel.MapMode::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                arenaClass,
            )
            asByteBuffer = memorySegmentClass.getMethod("asByteBuffer")
            force = memorySegmentClass.getMethod("force")
            supported = true
        } catch (_: Throwable) {
            supported = false
        }

        ofSharedMethod = ofShared
        mapMethod = map
        asByteBufferMethod = asByteBuffer
        forceMethod = force
        isSupported = supported
    }

    /**
     * Creates a new shared [java.lang.foreign.Arena] (`Arena.ofShared()`).
     * Shared arenas allow the mapped buffer to be accessed from multiple threads.
     */
    fun newSharedArena(): AutoCloseable = ofSharedMethod!!.invoke(null) as AutoCloseable

    /**
     * Maps the whole [channel] for reading and writing, tying the mapping's
     * lifetime to [arena] (`FileChannel.map(READ_WRITE, 0, size, arena)`).
     */
    fun map(channel: FileChannel, arena: AutoCloseable, size: Long): Any =
        mapMethod!!.invoke(channel, FileChannel.MapMode.READ_WRITE, 0L, size, arena)

    /**
     * Returns a [ByteBuffer] view over [segment] (`MemorySegment.asByteBuffer()`).
     */
    fun asByteBuffer(segment: Any): ByteBuffer = asByteBufferMethod!!.invoke(segment) as ByteBuffer

    /**
     * Flushes [segment] to storage (`MemorySegment.force()`).
     */
    fun force(segment: Any) {
        forceMethod!!.invoke(segment)
    }
}
