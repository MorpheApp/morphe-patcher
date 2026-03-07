/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

fun File.utf8Reader(): BufferedReader = this.bufferedReader(Charsets.UTF_8)

fun File.utf8Writer(): BufferedWriter = this.bufferedWriter(Charsets.UTF_8)