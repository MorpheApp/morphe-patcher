/*
 *  Copyright (C) 2022 github.com/REAndroid
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// https://github.com/REAndroid/APKEditor/blob/57b74530372eead7ffbfc1bcf1dd826db76a70ac/src/main/java/com/reandroid/apkeditor/Util.java
package app.morphe.patcher.apk

import java.io.File

internal object ApkEditorUtil {
    fun ensureUniqueFile(file: File): File {
        var file = file
        if (!file.exists()) {
            return file
        }
        val dir = file.parentFile
        var name = file.name
        var ext = ""
        if (file.isFile) {
            val i = name.lastIndexOf('.')
            if (i > 0) {
                ext = name.substring(i)
                name = name.substring(0, i)
            }
        }
        var i = 1
        while (i < 1000 && file.exists()) {
            val newName = name + "_" + i + ext
            if (dir == null) {
                file = File(newName)
            } else {
                file = File(dir, newName)
            }
            i++
        }
        return file
    }

    fun delete(file: File?) {
        if (file == null || !file.exists()) {
            return
        }
        if (file.isFile) {
            file.delete()
        } else if (file.isDirectory) {
            deleteDir(file)
        }
    }

    fun deleteDir(dir: File) {
        if (!dir.exists()) {
            return
        }
        if (dir.isFile) {
            dir.delete()
            return
        }
        if (!dir.isDirectory) {
            return
        }
        val files = dir.listFiles()
        if (files == null) {
            deleteEmptyDirectories(dir)
            return
        }
        for (file in files) {
            deleteDir(file)
        }
        deleteEmptyDirectories(dir)
    }

    fun deleteEmptyDirectories(dir: File?) {
        if (dir == null || !dir.isDirectory) {
            return
        }
        var filesList = dir.listFiles()
        if (filesList == null || filesList.size == 0) {
            dir.delete()
            return
        }
        var count = filesList.size
        for (i in 0..<count) {
            val file = filesList[i]
            if (file.isFile && file.length() != 0L) {
                return
            }
        }
        count = filesList.size
        for (i in 0..<count) {
            val file = filesList[i]
            if (file.isDirectory) {
                deleteEmptyDirectories(file)
            }
        }
        filesList = dir.listFiles()
        if (filesList == null || filesList.size == 0) {
            dir.delete()
        }
    }
}