package app.morphe.patcher.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createParentDirectories

internal object FileUtils {
    internal fun File.safelyMoveTo(dest: File) {
        val destPath = dest.toPath()
        destPath.createParentDirectories()
        Files.move(toPath(), destPath, StandardCopyOption.REPLACE_EXISTING)
    }

    internal fun File.safelyDelete() {
        if (exists()) {
            if (isFile) {
                Files.delete(toPath())
            } else if (!deleteRecursively()) {
                throw RuntimeException("Failed to recursively delete $name")
            }
        }
    }
}