package app.morphe.patcher.dex

import app.morphe.patcher.environment.EnvironmentUtils
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.Closeable
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

internal class MemoryBackedDexFile(private val channel: FileChannel, private val buf: MappedByteBuffer)
    : DexBackedDexFile(null, buf), Closeable {

    constructor(channel: FileChannel) : this(channel,
        channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size()))

    override fun close() {
        if (EnvironmentUtils.isWindowsEnvironment) DexUtils.unsafeUnmap(buf)
        channel.close()
    }
}