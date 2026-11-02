/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import app.morphe.patcher.logging.PhaseTimings
import app.morphe.patcher.logging.measure
import app.morphe.patcher.patch.PatchException
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.container.SpecTypePair

/** The type id of the resource id [id]. */
internal fun typeIdOf(id: Int) = (id ushr 16) and 0xff

/** The first resource id of the type [typeId] of the package [packageId]. */
internal fun firstIdOf(packageId: Int, typeId: Int) = (packageId shl 24) or (typeId shl 16)

/**
 * The type id a type new to a package is given: the one after [highestTypeId], the highest type id
 * of the ids of the package.
 *
 * @throws PatchException If the package has no type id left.
 */
internal fun newTypeId(highestTypeId: Int, type: String, packageName: String): Int {
    val typeId = highestTypeId + 1
    if (typeId > 0xff) throw PatchException("No type id is left for resources of the new type $type in $packageName")
    return typeId
}

/** The package id of the `id` attribute of the `<resources>` element of `public.xml`, such as `0x7f`. */
internal fun parsePackageId(id: String): Int =
    if (id.startsWith("0x") || id.startsWith("0X")) id.substring(2).toInt(16) else id.toInt()

/**
 * The public resource ids of a package, as the processors that give new resources an id and the
 * encoder use them.
 */
internal interface PublicIds {
    /**
     * Gives the resource [name] of [type] the next id of its type, unless it has one already.
     * Nothing happens if [name] is null.
     */
    fun createPublicId(type: String, name: String?)

    /** Renames the package the ids are declared for. */
    fun changePackageName(name: String)

    /** The ids the encoder declares in the resource table, by type and name. */
    fun idsToDeclare(): Map<Pair<String, String>, Int>
}

/**
 * The ids of a [PublicXmlManager], which declares every id of its `public.xml` to the encoder.
 */
internal class PublicXmlIds(val manager: PublicXmlManager) : PublicIds {
    override fun createPublicId(type: String, name: String?) = manager.createPublicId(type, name)

    override fun changePackageName(name: String) = manager.changePackageName(name)

    override fun idsToDeclare() = manager.getDefinedIds()
}

/**
 * The ids of a package read from its resource table instead of from its `public.xml`, the same ids
 * a [PublicXmlManager] reads from the file [PackageBlock.serializePublicXml] writes. Only the ids
 * this creates are declared to the encoder, since the table already has the others.
 *
 * The ids of a type are looked up the first time it is asked for, as a patch touches only a few
 * types and a large app has tens of thousands of resources.
 *
 * @param packageBlock The package, which must [mirrors].
 * @param timings The timings the lookups are measured into, as `tableIds`, if any.
 */
internal class TablePublicIds(
    packageBlock: PackageBlock,
    private val timings: PhaseTimings? = null,
) : PublicIds {
    /** Null once [releaseTable] let go of it. */
    private var packageBlock: PackageBlock? = packageBlock

    private class TypeIds(val ids: HashMap<String, Int>, var highest: Int)

    private val types = HashMap<String, TypeIds>()

    /**
     * The highest type id of the ids `public.xml` lists, which a type new to the package is given the
     * next one of, as by a [PublicXmlManager].
     */
    private var highestTypeId: Int? = null

    private fun highestTypeId(): Int = highestTypeId ?: timings.measure("tableIds") {
        table().specTypePairs.asSequence().filter { it.publicEntries().any() }.maxOfOrNull { it.id } ?: 0
    }

    /**
     * The types `public.xml` lists, in the order it first lists them, which is the order a
     * [PublicXmlManager] holds them in.
     */
    private val fileTypeOrder: Map<String, Int> by lazy {
        timings.measure("tableIds") {
            val order = HashMap<String, Int>()
            val table = table()
            table.specTypePairs.forEach { specTypePair ->
                val type = table.typeNameOf(specTypePair.id) ?: return@forEach
                if (type !in order && specTypePair.publicEntries().any()) order[type] = order.size
            }
            order
        }
    }

    /** The ids this created, by type in the order the type was first created in, and by name in creation order. */
    private val created = LinkedHashMap<String, LinkedHashMap<String, Int>>()

    private sealed interface Call {
        class Create(val type: String, val name: String) : Call
        class Rename(val name: String) : Call
    }

    private val calls = mutableListOf<Call>()

    private fun table() = packageBlock ?: throw IllegalStateException("The resource table was released")

    /**
     * The entries of the type that `public.xml` lists, in the order it lists them: the one with the
     * highest id first, then all of them by id, as [com.reandroid.arsc.container.SpecTypePair.serializePublicXml]
     * writes them. Entries without a name are left out.
     */
    private fun SpecTypePair.publicEntries(): Sequence<Pair<String, Int>> = sequence {
        val last = getResource(highestEntryId)
        val lastName = last?.name
        if (lastName != null) yield(lastName to last.resourceId)
        val iterator = resources
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (lastName != null && entry == last) continue
            entry.name?.let { yield(it to entry.resourceId) }
        }
    }

    private fun typeIds(type: String): TypeIds = types.getOrPut(type) {
        timings.measure("tableIds") {
            val table = table()
            val ids = HashMap<String, Int>()
            var highest = 0
            table.specTypePairs.forEach { specTypePair ->
                if (table.typeNameOf(specTypePair.id) != type) return@forEach
                specTypePair.publicEntries().forEach { (name, id) ->
                    if (id > highest) highest = id
                    ids[name] = id
                }
            }
            TypeIds(ids, highest)
        }
    }

    /** Whether [type] has an id for [name]. */
    fun idExists(type: String, name: String) = name in typeIds(type).ids

    /** The ids of [type] by name. */
    fun idsOf(type: String): Map<String, Int> = typeIds(type).ids.toMap()

    /** The highest id of [type], or 0 if it has none. */
    fun highestId(type: String): Int = typeIds(type).highest

    override fun createPublicId(type: String, name: String?) {
        if (name == null) return
        val typeIds = typeIds(type)
        if (name in typeIds.ids) return

        // As PublicXmlManager does it: a type without ids is new to the package, and is given the type id
        // after the highest one.
        val id = if (typeIds.highest != 0) typeIds.highest + 1 else {
            val table = table()
            val typeId = newTypeId(highestTypeId(), type, table.name)
            highestTypeId = typeId
            firstIdOf(table.id, typeId)
        }
        typeIds.highest = id
        typeIds.ids[name] = id
        created.getOrPut(type) { LinkedHashMap() }[name] = id
        calls += Call.Create(type, name)
    }

    override fun changePackageName(name: String) {
        calls += Call.Rename(name)
    }

    /**
     * The ids this created, in the order [PublicXmlManager.getDefinedIds] would list them: types
     * `public.xml` lists in its order, then new types in the order they were created in.
     */
    override fun idsToDeclare(): Map<Pair<String, String>, Int> {
        if (created.isEmpty()) return emptyMap()
        val order = fileTypeOrder
        val newTypes = created.keys.filter { it !in order }
        val sortedTypes = created.keys.sortedBy { order[it] ?: (order.size + newTypes.indexOf(it)) }
        return buildMap {
            sortedTypes.forEach { type -> created.getValue(type).forEach { (name, id) -> put(type to name, id) } }
        }
    }

    /**
     * Releases the resource table, which the encoder goes on to change. The lookups built so far
     * stay, while a type not looked up yet can no longer be.
     */
    fun releaseTable() {
        // Settled while the table is still at hand, since idsToDeclare needs it.
        if (created.isNotEmpty()) fileTypeOrder
        packageBlock = null
    }

    /**
     * Makes the calls this was given on [target], which gives a [PublicXmlManager] reading the
     * `public.xml` of the same table the same ids.
     */
    fun replayOn(target: PublicIds) = calls.forEach { call ->
        when (call) {
            is Call.Create -> target.createPublicId(call.type, call.name)
            is Call.Rename -> target.changePackageName(call.name)
        }
    }

    companion object {
        /**
         * Whether the `public.xml` of [packageBlock] can be read from its table. A [PublicXmlManager]
         * cannot read the file of a package without a name or an id, of a package whose ids do not
         * fit in an [Int], or with a type without a name, so such a package keeps the file.
         */
        fun mirrors(packageBlock: PackageBlock): Boolean =
            packageBlock.name != null && packageBlock.id in 1..0x7f &&
                    packageBlock.specTypePairs.asSequence().all { packageBlock.typeNameOf(it.id) != null }
    }
}
