/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [MappedFile]. These exercise whichever implementation is selected on the
 * running JDK: the FFM-backed mapping on JDK 22+, or the [java.io.RandomAccessFile]
 * fallback otherwise.
 */
internal class MappedFileTest {
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        file = File.createTempFile("mapped-file-test", ".bin").apply { deleteOnExit() }
    }

    @AfterTest
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `buffer covers the whole file`() {
        file.writeBytes(ByteArray(64))

        MappedFile.mapReadWrite(file).use { mapped ->
            assertEquals(64, mapped.buffer.capacity())
        }
    }

    @Test
    fun `reads existing file content`() {
        val bytes = byteArrayOf(0x44, 0x33, 0x22, 0x11)
        file.writeBytes(bytes)

        MappedFile.mapReadWrite(file).use { mapped ->
            val value = mapped.buffer.order(ByteOrder.LITTLE_ENDIAN).getInt(0)
            assertEquals(0x11223344, value)
        }
    }

    @Test
    fun `writes are persisted to disk after force and close`() {
        file.writeBytes(ByteArray(4))

        MappedFile.mapReadWrite(file).use { mapped ->
            mapped.buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x11223344)
            mapped.force()
        }

        val persisted = file.readBytes()
        assertEquals(0x44, persisted[0].toInt() and 0xFF)
        assertEquals(0x33, persisted[1].toInt() and 0xFF)
        assertEquals(0x22, persisted[2].toInt() and 0xFF)
        assertEquals(0x11, persisted[3].toInt() and 0xFF)
    }

    @Test
    fun `the mapped file can be deleted after close`() {
        // On Windows an active mapping locks the file; closing the mapping must release
        // it so the file can be deleted. On other platforms this verifies the handle is
        // released cleanly.
        file.writeBytes(ByteArray(16))

        MappedFile.mapReadWrite(file).use { mapped ->
            mapped.buffer.putInt(0, 1)
        }

        assertTrue(file.delete(), "file should be deletable after the mapping is closed")
        assertFalse(file.exists())
    }

    @Test
    fun `close is idempotent`() {
        file.writeBytes(ByteArray(8))

        val mapped = MappedFile.mapReadWrite(file)
        mapped.close()
        // A second close must not throw.
        mapped.close()
    }

    @Test
    fun `buffer is readable from another thread`() {
        // The mapping is shared, so reads from a thread other than the creator must work.
        // With a confined FFM arena this would throw; with a shared arena it succeeds.
        file.writeBytes(ByteArray(4))

        MappedFile.mapReadWrite(file).use { mapped ->
            mapped.buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x0BADF00D)

            var readValue = 0
            thread {
                readValue = mapped.buffer.order(ByteOrder.LITTLE_ENDIAN).getInt(0)
            }.join()

            assertEquals(0x0BADF00D, readValue)
        }
    }

    @Test
    fun `FFM is used when running on JDK 21 or newer`() {
        // Confirms the selection logic actually picks the FFM implementation on capable
        // runtimes instead of silently falling back. Tests always run on a desktop JDK,
        // so Runtime.version() is available here.
        val featureVersion = Runtime.version().feature()
        assumeTrue(featureVersion >= 21)

        assertTrue(
            MappedFile.isFfmSupported,
            "FFM support should be enabled when the runtime feature version (" +
                "$featureVersion) is 21 or newer",
        )
    }

    @Test
    fun `Reflection is used when running on JDK 20 or older`() {
        val featureVersion = Runtime.version().feature()
        assumeTrue(featureVersion < 21)

        assertFalse(
            MappedFile.isFfmSupported,
            "FFM support should not be enabled when the runtime feature version (" +
                    "$featureVersion) is 20 or older",
        )
    }

    @Test
    fun `accessing the buffer after close is rejected on the FFM path`() {
        // The FFM implementation deterministically unmaps on close, after which the
        // buffer must no longer be usable. The fallback cannot offer this guarantee.
        if (!MappedFile.isFfmSupported) return

        file.writeBytes(ByteArray(8))

        val mapped = MappedFile.mapReadWrite(file)
        mapped.close()

        assertFailsWith<IllegalStateException> {
            mapped.buffer.getInt(0)
        }
    }
}
