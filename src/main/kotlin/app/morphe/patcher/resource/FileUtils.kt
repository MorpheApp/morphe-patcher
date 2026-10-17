/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import com.reandroid.json.JSONItem
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

fun File.utf8Reader(): BufferedReader = this.bufferedReader(Charsets.UTF_8)

fun File.utf8Writer(): BufferedWriter = this.bufferedWriter(Charsets.UTF_8)

/**
 * Writes [json] to this file as [JSONItem.write] does, but through a buffer. [JSONItem.write]
 * hands each character to the stream writer on its own, which is slow on Android.
 */
internal fun File.writeJson(json: JSONItem) {
    absoluteFile.parentFile?.mkdirs()
    utf8Writer().use { writer -> json.write(writer, 2, 0) }
}
