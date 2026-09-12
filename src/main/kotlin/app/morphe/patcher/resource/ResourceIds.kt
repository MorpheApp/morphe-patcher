/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import app.morphe.patcher.patch.PatchException

/**
 * The resource ids of the APK being patched, read from its resource table on first use.
 *
 * Available whether or not resources are decoded, so fingerprints of bytecode patches can refer to
 * resource ids without depending on a resource patch. Ids are those of the input APK; resources a
 * patch adds later are not included.
 */
class ResourceIds internal constructor(load: () -> Map<String, Long>) {
    private val ids: Map<String, Long> by lazy(load)

    /**
     * @return The id of the resource, or `null` if the APK has no such resource.
     */
    fun getOrNull(type: ResourceType, name: String): Long? = ids[key(type, name)]

    /**
     * @return The id of the resource.
     * @throws PatchException If the APK has no such resource.
     */
    operator fun get(type: ResourceType, name: String): Long =
        getOrNull(type, name) ?: throw PatchException("Could not find resource type: $type name: $name")

    /**
     * @return Whether the APK has the resource.
     */
    fun has(type: ResourceType, name: String) = getOrNull(type, name) != null

    internal companion object {
        /** The ids of the APK currently being patched, for lookups that have no patch context at hand. */
        @Volatile
        internal var current: ResourceIds? = null

        internal fun key(type: ResourceType, name: String) = "${type.value}/$name"
    }
}

private fun current() = ResourceIds.current
    ?: throw PatchException("Resource ids are only available while an APK is being patched")

/**
 * The id of a resource of the APK being patched.
 *
 * @throws PatchException If the APK has no such resource, or no APK is being patched.
 */
fun resourceId(type: ResourceType, name: String): Long = current()[type, name]

/**
 * Whether the APK being patched has the resource.
 *
 * @throws PatchException If no APK is being patched.
 */
fun hasResourceId(type: ResourceType, name: String): Boolean = current().has(type, name)
