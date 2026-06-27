package app.morphe.patcher.dex

import sun.misc.Unsafe
import java.nio.MappedByteBuffer

object DexUtils {
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