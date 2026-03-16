/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import com.reandroid.json.JSONArray

/**
 * Bidirectional mapping between original APK entry names and on-disk alias paths.
 *
 * Parsed from `path-map.json` which ARSCLib writes during decoding.
 * The JSON is an array of objects with `name` and `alias` fields:
 * - `name`: the original (often obfuscated) path inside the APK (e.g. `res/-5N.png`)
 * - `alias`: the human-readable path ARSCLib writes to disk (e.g. `res/drawable-mdpi/drawable_0x7f080695.png`)
 *
 * Entries where name and alias are identical are not recorded by ARSCLib.
 */
internal class PathMap(pathMapJson: String) {

    /** Original APK name → on-disk alias. */
    private val nameToAlias: Map<String, String>

    /** On-disk alias → original APK name. */
    private val aliasToName: Map<String, String>

    init {
        val nameToAliasMap = mutableMapOf<String, String>()
        val aliasToNameMap = mutableMapOf<String, String>()

        val array = JSONArray(pathMapJson)
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val name = sanitizePath(entry.getString("name"))
            val alias = sanitizePath(entry.getString("alias"))
            if (name != alias) {
                nameToAliasMap[name] = alias
                aliasToNameMap[alias] = name
            }
        }

        nameToAlias = nameToAliasMap
        aliasToName = aliasToNameMap
    }

    /**
     * Given an on-disk alias path, return the original APK name,
     * or `null` if no mapping exists (the path is the same in both).
     */
    fun getOriginalName(alias: String): String? = aliasToName[sanitizePath(alias)]

    /**
     * Given an original APK name, return the on-disk alias path,
     * or `null` if no mapping exists (the path is the same in both).
     */
    fun getAlias(name: String): String? = nameToAlias[sanitizePath(name)]

    companion object {
        val EMPTY = PathMap("[]")

        private fun sanitizePath(path: String): String {
            var sanitized = path.replace('\\', '/').trim()
            while (sanitized.startsWith("/")) {
                sanitized = sanitized.substring(1)
            }
            return sanitized
        }
    }
}

