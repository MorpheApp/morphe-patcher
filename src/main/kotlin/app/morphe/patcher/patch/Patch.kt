@file:Suppress("MemberVisibilityCanBePrivate", "unused", "DEPRECATION_ERROR")

package app.morphe.patcher.patch

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherBehavior
import app.morphe.patcher.PatcherContext
import app.morphe.patcher.dex.DexReadWrite
import app.morphe.patcher.resolvePatcherBehaviors
import dalvik.system.DexClassLoader
import java.io.File
import java.io.InputStream
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.function.Supplier
import java.util.jar.JarFile
import java.util.logging.Logger

@Deprecated("Use Compatibility instead", level = DeprecationLevel.HIDDEN)
typealias PackageName = String
@Deprecated("Use Compatibility instead", level = DeprecationLevel.HIDDEN)
typealias VersionName = String
@Deprecated("Use Compatibility instead", level = DeprecationLevel.HIDDEN)
typealias Package = Pair<PackageName, Set<VersionName>?>

/**
 * A patch.
 *
 * @param C The [PatchContext] to execute and finalize the patch with.
 * @param name The name of the patch.
 *   If null, the patch is named "Patch" and will not be loaded by [PatchLoader].
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 * @param dependencies Other patches this patch depends on.
 * @param compatibility The packages the patch is compatible with.
 *   If null, the patch is compatible with all packages.
 * @param options The options of the patch.
 * @param executeBlock The execution block of the patch.
 * @param finalizeBlock The finalizing block of the patch. Called after all patches have been executed,
 * in reverse order of execution.
 * @param availability Optional resolver that decides the patch's [PatchAvailability] for a given
 *   [InstallerType] and [ApkArchitecture]. When null, callers should fall back to [default].
 * @param category Optional group this patch belongs to, used by callers to section their patch
 *   list. Free-form, so a bundle picks its own taxonomy. Null leaves the patch ungrouped.
 *
 * @constructor Create a new patch.
 */
sealed class Patch<C : PatchContext<*>>(
    val name: String?,
    val description: String?,
    val default: Boolean,
    val dependencies: Set<Patch<*>>,
    val compatibility: List<Compatibility>?,
    options: Set<Option<*>>,
    private val executeBlock: (C) -> Unit,
    // Must be internal and nullable, so that Patcher.invoke can check,
    // if a patch has a finalizing block in order to not emit it twice.
    internal var finalizeBlock: ((C) -> Unit)?,
    // Additive fields. Trailing position with a default keeps binary compatibility for existing
    // pre-compiled patch bundles: they call the previous constructor arity, and Kotlin generates
    // an overload that fills these in as null.
    val availability: AvailabilityResolver? = null,
    val category: String? = null,
) {

    @Deprecated("Use constructor with Compatibility object", level = DeprecationLevel.HIDDEN)
    constructor(
        name: String?,
        description: String?,
        use: Boolean,
        dependencies: Set<Patch<*>>,
        compatiblePackages: Set<Pair<String, Set<String>?>>,
        options: Set<Option<*>>,
        executeBlock: (C) -> Unit,
        finalizeBlock: ((C) -> Unit)?,
    ) : this(
        name = name,
        description = description,
        default = use,
        dependencies = dependencies,
        compatibility = Compatibility.fromLegacy(compatiblePackages),
        options = options,
        executeBlock = executeBlock,
        finalizeBlock = finalizeBlock
    )

    /**
     * Legacy format. Experimental app targets are not present in this format.
     */
    @Deprecated("Instead use compatibility", level = DeprecationLevel.HIDDEN)
    val compatiblePackages: Set<Pair<String, Set<String>?>>? by lazy {
        compatibility?.mapNotNull { it.legacy }?.toSet()
    }

    @Deprecated(
        message = "Use 'default' instead of 'use'",
        replaceWith = ReplaceWith("default"),
        level = DeprecationLevel.HIDDEN
    )
    val use: Boolean get() = default

    /**
     * The options of the patch.
     */
    val options = Options(options)

    /**
     * The patch bundle the patch was loaded from by [PatchLoader], or null if it was not loaded from one.
     */
    internal var bundle: PatchBundleInfo? = null

    /**
     * Whether the patch is universal but was declared with default true, which [default] ignores.
     * Loading such a patch fails if [PatcherBehavior.STRICT_UNIVERSAL_PATCH_DEFAULT] applies to it.
     */
    internal var declaredUniversalWithDefault = false

    /**
     * Where the patch was declared, such as `app.morphe.patches.ExamplePatchKt.<clinit>(ExamplePatch.kt:12)`,
     * or null if it is not known. Only known for patches with [declaredUniversalWithDefault],
     * to name them in the warning about it.
     */
    internal var declaredAt: StackTraceElement? = null

    /**
     * Calls the execution block of the patch.
     * This function is called by [Patcher.invoke].
     *
     * @param context The [PatcherContext] to get the [PatchContext] from to execute the patch with.
     */
    internal abstract fun execute(context: PatcherContext)

    /**
     * Calls the execution block of the patch.
     *
     * @param context The [PatchContext] to execute the patch with.
     */
    fun execute(context: C) = executeBlock(context)

    /**
     * The name of the patch, or if it has no name the class name of its execution block.
     * Patches are declared with a DSL, so an unnamed patch has no other way to be identified.
     */
    internal val logName: String
        get() = name ?: executeBlock.javaClass.name

    /**
     * Calls the finalizing block of the patch.
     * This function is called by [Patcher.invoke].
     *
     * @param context The [PatcherContext] to get the [PatchContext] from to finalize the patch with.
     */
    internal abstract fun finalize(context: PatcherContext)

    /**
     * Calls the finalizing block of the patch.
     *
     * @param context The [PatchContext] to finalize the patch with.
     */
    fun finalize(context: C) {
        finalizeBlock?.invoke(context)
    }

    override fun toString() = name ?: 
        "Patch@${System.identityHashCode(this)}"
}

internal fun Patch<*>.anyRecursively(
    visited: MutableSet<Patch<*>> = mutableSetOf(),
    predicate: (Patch<*>) -> Boolean,
): Boolean {
    if (this in visited) return false

    if (predicate(this)) return true

    visited += this

    return dependencies.any { it.anyRecursively(visited, predicate) }
}

internal fun Iterable<Patch<*>>.forEachRecursively(
    visited: MutableSet<Patch<*>> = mutableSetOf(),
    action: (Patch<*>) -> Unit,
): Unit = forEach {
    if (it in visited) return@forEach

    visited += it
    action(it)

    it.dependencies.forEachRecursively(visited, action)
}

/**
 * A bytecode patch.
 *
 * @param name The name of the patch.
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 * @property compatibility The packages the patch is compatible with.
 *   If null, then the patch is universal and could be applied to all apps.
 * @param dependencies Other patches this patch depends on.
 * @param options The options of the patch.
 * @param extensionStreamProviders Providers of extension input streams, evaluated when the patch is
 *   executed. Each provider yields the extension input streams (precompiled DEX files) to merge into the
 *   patched app before this patch runs. A single extension added with [BytecodePatchBuilder.extendWith]
 *   is stored as a provider yielding one stream, while [BytecodePatchBuilder.extendWithAll] allows a
 *   variable number of extensions whose count is not known until patch time.
 * @param executeBlock The execution block of the patch.
 * @param finalizeBlock The finalizing block of the patch. Called after all patches have
 *   been executed, in reverse order of execution.
 */
class BytecodePatch internal constructor(
    name: String?,
    description: String?,
    default: Boolean,
    compatibility: List<Compatibility>?,
    dependencies: Set<Patch<*>>,
    options: Set<Option<*>>,
    internal val extensionStreamProviders: List<Supplier<out Iterable<Supplier<InputStream>>>>,
    executeBlock: (BytecodePatchContext) -> Unit,
    finalizeBlock: ((BytecodePatchContext) -> Unit)?,
    availability: AvailabilityResolver? = null,
    category: String? = null,
) : Patch<BytecodePatchContext>(
    name,
    description,
    default,
    dependencies,
    compatibility,
    options,
    executeBlock,
    finalizeBlock,
    availability,
    category,
) {

    @Deprecated("Use constructor with extensionInputStreams parameter", level = DeprecationLevel.HIDDEN)
    constructor(
        name: String?,
        description: String?,
        default: Boolean,
        compatibility: List<Compatibility>?,
        dependencies: Set<Patch<*>>,
        options: Set<Option<*>>,
        extensionInputStream: Supplier<InputStream>?,
        executeBlock: (BytecodePatchContext) -> Unit,
        finalizeBlock: ((BytecodePatchContext) -> Unit)?,
    ) : this(
        name = name,
        description = description,
        default = default,
        compatibility = compatibility,
        dependencies = dependencies,
        options = options,
        extensionStreamProviders = extensionInputStream?.let { listOf(Supplier { listOf(it) }) } ?: emptyList(),
        executeBlock = executeBlock,
        finalizeBlock = finalizeBlock
    )

    @Deprecated("Use constructor with Compatibility object", level = DeprecationLevel.HIDDEN)
    constructor(
        name: String?,
        description: String?,
        use: Boolean = true,
        compatiblePackages: Set<Pair<String, Set<String>?>>?,
        dependencies: Set<Patch<*>>,
        options: Set<Option<*>>,
        extensionInputStream: Supplier<InputStream>?,
        executeBlock: (BytecodePatchContext) -> Unit,
        finalizeBlock: ((BytecodePatchContext) -> Unit)?,
    ) : this(
        name = name,
        description = description,
        default = use,
        compatibility = Compatibility.fromLegacy(compatiblePackages),
        dependencies = dependencies,
        options = options,
        extensionStreamProviders = extensionInputStream?.let { listOf(Supplier { listOf(it) }) } ?: emptyList(),
        executeBlock = executeBlock,
        finalizeBlock = finalizeBlock
    )

    @Deprecated("extensionInputStream will be made private in a future release.", level = DeprecationLevel.HIDDEN)
    fun getExtensionInputStream(): Supplier<InputStream>? =
        extensionStreamProviders.firstOrNull()?.let { provider -> Supplier { provider.get().first().get() } }

    override fun execute(context: PatcherContext) = with(context.bytecodeContext) {
        mergeExtension(this@BytecodePatch)
        execute(this)
    }

    override fun finalize(context: PatcherContext) = finalize(context.bytecodeContext)

    override fun toString() = name ?: "Bytecode${super.toString()}"
}

/**
 * A raw resource patch.
 *
 * @param name The name of the patch.
 * If null, the patch is named "Patch" and will not be loaded by [PatchLoader].
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 * @param compatibility The packages the patch is compatible with.
 * If null, the patch is compatible with all packages.
 * @param dependencies Other patches this patch depends on.
 * @param options The options of the patch.
 * @param executeBlock The execution block of the patch.
 * @param finalizeBlock The finalizing block of the patch. Called after all patches have been executed,
 * in reverse order of execution.
 *
 * @constructor Create a new raw resource patch.
 */
class RawResourcePatch internal constructor(
    name: String?,
    description: String?,
    default: Boolean,
    compatibility: List<Compatibility>?,
    dependencies: Set<Patch<*>>,
    options: Set<Option<*>>,
    executeBlock: (ResourcePatchContext) -> Unit,
    finalizeBlock: ((ResourcePatchContext) -> Unit)?,
    availability: AvailabilityResolver? = null,
    category: String? = null,
) : Patch<ResourcePatchContext>(
    name,
    description,
    default,
    dependencies,
    compatibility,
    options,
    executeBlock,
    finalizeBlock,
    availability,
    category,
) {

    @Deprecated("Use constructor with Compatibility object", level = DeprecationLevel.HIDDEN)
    constructor(
        name: String?,
        description: String?,
        use: Boolean,
        compatiblePackages: Set<Pair<String, Set<String>?>>?,
        dependencies: Set<Patch<*>>,
        options: Set<Option<*>>,
        executeBlock: (ResourcePatchContext) -> Unit,
        finalizeBlock: ((ResourcePatchContext) -> Unit)?,
    ) : this(
        name = name,
        description = description,
        default = use,
        compatibility = Compatibility.fromLegacy(compatiblePackages),
        dependencies = dependencies,
        options = options,
        executeBlock = executeBlock,
        finalizeBlock = finalizeBlock
    )

    override fun execute(context: PatcherContext) = execute(context.resourceContext)

    override fun finalize(context: PatcherContext) = finalize(context.resourceContext)

    override fun toString() = name ?: "RawResource${super.toString()}"
}

/**
 * A resource patch.
 *
 * @param name The name of the patch.
 * If null, the patch is named "Patch" and will not be loaded by [PatchLoader].
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 * @param compatibility The packages the patch is compatible with.
 * If null, the patch is compatible with all packages.
 * @param dependencies Other patches this patch depends on.
 * @param options The options of the patch.
 * @param executeBlock The execution block of the patch.
 * @param finalizeBlock The finalizing block of the patch. Called after all patches have been executed,
 * in reverse order of execution.
 *
 * @constructor Create a new resource patch.
 */
class ResourcePatch internal constructor(
    name: String?,
    description: String?,
    default: Boolean,
    compatibility: List<Compatibility>?,
    dependencies: Set<Patch<*>>,
    options: Set<Option<*>>,
    executeBlock: (ResourcePatchContext) -> Unit,
    finalizeBlock: ((ResourcePatchContext) -> Unit)?,
    availability: AvailabilityResolver? = null,
    category: String? = null,
) : Patch<ResourcePatchContext>(
    name,
    description,
    default,
    dependencies,
    compatibility,
    options,
    executeBlock,
    finalizeBlock,
    availability,
    category,
) {

    @Deprecated("Use constructor with Compatibility object", level = DeprecationLevel.HIDDEN)
    constructor(
        name: String?,
        description: String?,
        use: Boolean,
        compatiblePackages: Set<Pair<String, Set<String>?>>?,
        dependencies: Set<Patch<*>>,
        options: Set<Option<*>>,
        executeBlock: (ResourcePatchContext) -> Unit,
        finalizeBlock: ((ResourcePatchContext) -> Unit)?,
    ) : this(
        name = name,
        description = description,
        default = use,
        compatibility = Compatibility.fromLegacy(compatiblePackages),
        dependencies = dependencies,
        options = options,
        executeBlock = executeBlock,
        finalizeBlock = finalizeBlock
    )

    override fun execute(context: PatcherContext) = execute(context.resourceContext)

    override fun finalize(context: PatcherContext) = finalize(context.resourceContext)

    override fun toString() = name ?: "Resource${super.toString()}"
}

/**
 * A [Patch] builder.
 *
 * @param C The [PatchContext] to execute and finalize the patch with.
 * @param name The name of the patch.
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 * @property compatibility The packages the patch is compatible with.
 *   If null, then the patch is universal and could be applied to all apps.
 * @property dependencies Other patches this patch depends on.
 * @property options The options of the patch.
 * @property executeBlock The execution block of the patch.
 * @property finalizeBlock The finalizing block of the patch. Called after all patches
 *   have been executed, in reverse order of execution.
 *
 * @constructor Create a new [Patch] builder.
 */
sealed class PatchBuilder<C : PatchContext<*>>(
    protected val name: String?,
    protected val description: String?,
    protected val default: Boolean,
) {
    protected var compatibility: MutableList<Compatibility>? = null
    protected var dependencies = mutableSetOf<Patch<*>>()
    protected val options = mutableSetOf<Option<*>>()

    protected var executeBlock: ((C) -> Unit) = { }
    protected var finalizeBlock: ((C) -> Unit)? = null
    protected var availability: AvailabilityResolver? = null
    protected var category: String? = null

    @Deprecated(
        message = "Use 'default' instead of 'use'",
        replaceWith = ReplaceWith("default"),
        level = DeprecationLevel.HIDDEN
    )
    protected val use: Boolean get() = default

    /**
     * Add an option to the patch.
     *
     * @return The added option.
     */
    operator fun <T> Option<T>.invoke() = apply {
        options += this
    }

    /**
     * Create a package a patch is compatible with.
     *
     * @param versions The versions of the package.
     */
    operator fun String.invoke(vararg versions: String) = invoke(versions.toSet())

    /**
     * Create a package a patch is compatible with.
     *
     * @param versions The versions of the package.
     */
    private operator fun String.invoke(versions: Set<String>? = null) = this to versions

    @Deprecated("Instead use Compatibility object", level = DeprecationLevel.HIDDEN)
    protected fun getCompatiblePackages(): Set<Pair<String, Set<String>?>>? {
        return compatibility?.mapNotNull { it.legacy }?.toSet()
    }

    @Deprecated("Instead use Compatibility object", level = DeprecationLevel.HIDDEN)
    protected fun setCompatiblePackages(packages: Set<Pair<String, Set<String>?>>?) {
        compatibility = packages?.map { Compatibility.fromLegacy(it) }?.toMutableList()
    }

    @Deprecated("Instead use getExecuteBlock()", level = DeprecationLevel.HIDDEN)
    protected fun getExecutionBlock(): (C) -> Unit {
        return executeBlock
    }

    @Deprecated("Instead use setExecuteBlock()", level = DeprecationLevel.HIDDEN)
    protected fun setExecutionBlock(block: (C) -> Unit) {
        executeBlock = block
    }

    /**
     * Add packages the patch is compatible with.
     *
     * @param compatibility The compatibility of this patch.
     */
    fun compatibleWith(vararg compatibility: Compatibility) {
        if (this.compatibility == null) {
            this.compatibility = mutableListOf()
        }

        this.compatibility!!.addAll(compatibility)
    }

    /**
     * Add packages the patch is compatible with.
     *
     * @param packages The packages the patch is compatible with.
     */
    @Deprecated("Instead use Compatibility object", level = DeprecationLevel.HIDDEN)
    fun compatibleWith(vararg packages: Pair<String, Set<String>?>) {
        if (compatibility == null) {
            compatibility = mutableListOf()
        }

        compatibility!!.addAll(packages.map { Compatibility.fromLegacy(it) })
    }

    /**
     * Set the compatible packages of the patch.
     *
     * @param packages The packages the patch is compatible with.
     */
    @Deprecated("Instead use Compatibility object", level = DeprecationLevel.HIDDEN)
    fun compatibleWith(vararg packages: String) {
        if (compatibility == null) {
            compatibility = mutableListOf()
        }

        compatibility!!.addAll(packages.map { Compatibility.fromLegacy(it to null) })
    }

    /**
     * Add dependencies to the patch.
     *
     * @param patches The patches the patch depends on.
     */
    fun dependsOn(vararg patches: Patch<*>) {
        dependencies += patches
    }

    /**
     * Set the execution block of the patch.
     *
     * @param block The execution block of the patch.
     */
    fun execute(block: C.() -> Unit) {
        executeBlock = block
    }

    /**
     * Set the finalizing block of the patch.
     *
     * @param block The finalizing block of the patch.
     */
    fun finalize(block: C.() -> Unit) {
        finalizeBlock = block
    }

    /**
     * Declare how this patch behaves for a given install target.
     *
     * The [block] receives the [InstallerType] and [ApkArchitecture] chosen by the caller and
     * returns a [PatchAvailability]. It runs off the patching hot path (before execute), so it
     * must be pure and cheap. Patches that do not call [availability] fall back to their
     * [default] flag.
     *
     * @param block Resolver evaluated by the caller (Manager / CLI) before patch selection is
     *   finalized.
     */
    fun availability(block: AvailabilityResolver) {
        availability = block
    }

    /**
     * Declare the group this patch belongs to, so callers can section a long patch list.
     *
     * The taxonomy belongs to the bundle rather than to the patcher: [name] is free-form and is
     * matched by callers as an opaque key. Patches that do not call this stay ungrouped.
     *
     * @param name The category name, as the user reads it.
     */
    fun category(name: String) {
        category = name
    }

    /**
     * Whether the patch is universal but declared with default true.
     */
    internal val isUniversalWithDefault
        get() = name != null && default &&
            (compatibility == null || compatibility!!.any { it.packageName == null })

    /**
     * The bundle is not known yet, so universal patches declared with default true are only warned
     * about, or fail, once they are loaded, see [PatchLoader].
     */
    internal fun resolveDefaultValue() = default && !isUniversalWithDefault

    /**
     * Build the patch.
     *
     * @return The built patch.
     */
    internal abstract fun build(): Patch<C>
}

/**
 * Builds a [Patch].
 *
 * @param B The [PatchBuilder] to build the patch with.
 * @param block The block to build the patch.
 *
 * @return The built [Patch].
 */
private fun <B : PatchBuilder<*>> B.buildPatch(block: B.() -> Unit = {}) = apply(block).build().also {
    if (!isUniversalWithDefault) return@also

    it.declaredUniversalWithDefault = true
    // The patch is declared by whoever called the patch functions of this file. Patches are declared
    // with the DSL, so this is the only hint of the class or file that declares them.
    val stackTrace = Throwable().stackTrace
    val builderClass = stackTrace.firstOrNull()?.className
    it.declaredAt = stackTrace.dropWhile { frame -> frame.className == builderClass }.firstOrNull()
}

/**
 * A [BytecodePatch] builder.
 *
 * @param name The name of the patch.
 * If null, the patch is named "Patch" and will not be loaded by [PatchLoader].
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 * @property extensionInputStreams Extension input streams registered with [extendWith]. Each is a
 * precompiled DEX file merged into the patched app before this patch is executed.
 * @property extensionStreamProviders Providers of extension input streams registered with [extendWithAll],
 * evaluated at patch time, for when the number of extensions to merge is not known until then.
 *
 * @constructor Create a new [BytecodePatchBuilder] builder.
 */
class BytecodePatchBuilder internal constructor(
    name: String?,
    description: String?,
    default: Boolean,
) : PatchBuilder<BytecodePatchContext>(name, description, default) {
    /**
     * Extension input streams registered with [extendWith]. Each is a precompiled DEX file that is
     * merged into the patched app before this patch runs. These are combined into the built
     * [BytecodePatch]'s providers when the patch is built.
     */
    internal val extensionInputStreams: MutableList<Supplier<InputStream>> = mutableListOf()

    /**
     * Providers of extension input streams registered with [extendWithAll], evaluated at patch time.
     * Use these when the number of extensions to merge is not known until then, for example when the
     * extensions are derived by a dependency patch that has already executed.
     */
    internal val extensionStreamProviders: MutableList<Supplier<out Iterable<Supplier<InputStream>>>> = mutableListOf()

    // Inlining is necessary to get the class loader that loaded the patch
    // to load the extension from the resources.
    /**
     * Set the extension of the patch.
     *
     * @param extension The name of the extension resource.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun extendWith(extension: String) = apply {
        val classLoader = object {}.javaClass.classLoader

        extendWith {
            classLoader.getResourceAsStream(extension)
                ?: throw PatchException("Extension \"$extension\" not found")
        }
    }

    fun extendWith(extension: Supplier<InputStream>) = apply {
        extensionInputStreams.add(extension)
    }

    /**
     * Extend the patch with a variable number of extensions that are resolved at patch time.
     *
     * Unlike [extendWith], the [provider] is not evaluated when the patch is built but when the patch
     * is executed, before the patch's `execute` block runs. This makes it possible to merge a number
     * of extensions that is not known until patch time, such as extensions derived by a dependency
     * patch that has already executed.
     *
     * @param provider A provider, evaluated at patch time, of the extension input streams to merge.
     */
    fun extendWithAll(provider: Supplier<out Iterable<Supplier<InputStream>>>) = apply {
        extensionStreamProviders.add(provider)
    }

    @Deprecated(
        "Kept only for backwards compatibility with older patch bundles, use extendWith(Supplier<InputStream>).",
        level = DeprecationLevel.HIDDEN
    )
    fun setExtensionInputStream(supplier: Supplier<InputStream>) {
        extendWith(supplier)
    }

    @Deprecated(
        "Kept only for backwards compatibility with older patch bundles.",
        level = DeprecationLevel.HIDDEN
    )
    fun getExtensionInputStream(): Supplier<InputStream>? = extensionInputStreams.firstOrNull()

    override fun build() = BytecodePatch(
        name = name,
        description = description,
        default = resolveDefaultValue(),
        compatibility = compatibility,
        dependencies = dependencies,
        options = options,
        extensionStreamProviders = buildList {
            // Each eager extension becomes a provider yielding a single stream, merged before the
            // patch-time providers registered with extendWithAll.
            extensionInputStreams.forEach { extension -> add(Supplier { listOf(extension) }) }
            addAll(extensionStreamProviders)
        },
        executeBlock = executeBlock,
        finalizeBlock = finalizeBlock,
        availability = availability,
        category = category,
    )
}

/**
 * Create a new [BytecodePatch].
 *
 * @param name The name of the patch.
 * If null, the patch is named "Patch" and will not be loaded by [PatchLoader].
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 * @param block The block to build the patch.
 *
 * @return The created [BytecodePatch].
 */
fun bytecodePatch(
    name: String? = null,
    description: String? = null,
    default: Boolean = true,
    block: BytecodePatchBuilder.() -> Unit = {},
) = BytecodePatchBuilder(name, description, default).buildPatch(block) as BytecodePatch

/**
 * A [RawResourcePatch] builder.
 *
 * @param name The name of the patch.
 * If null, the patch is named "Patch" and will not be loaded by [PatchLoader].
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 *
 * @constructor Create a new [RawResourcePatch] builder.
 */
class RawResourcePatchBuilder internal constructor(
    name: String?,
    description: String?,
    default: Boolean = true,
) : PatchBuilder<ResourcePatchContext>(name, description, default) {
    override fun build() = RawResourcePatch(
        name,
        description,
        resolveDefaultValue(),
        compatibility,
        dependencies,
        options,
        executeBlock,
        finalizeBlock,
        availability,
        category,
    )
}

/**
 * Create a new [RawResourcePatch].
 *
 * @param name The name of the patch.
 * If null, the patch is named "Patch" and will not be loaded by [PatchLoader].
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 * @param block The block to build the patch.
 *
 * @return The created [RawResourcePatch].
 */
fun rawResourcePatch(
    name: String? = null,
    description: String? = null,
    default: Boolean = true,
    block: RawResourcePatchBuilder.() -> Unit = {},
) = RawResourcePatchBuilder(name, description, default).buildPatch(block) as RawResourcePatch

/**
 * A [ResourcePatch] builder.
 *
 * @param name The name of the patch.
 * If null, the patch is named "Patch" and will not be loaded by [PatchLoader].
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 *
 * @constructor Create a new [ResourcePatch] builder.
 */
class ResourcePatchBuilder internal constructor(
    name: String?,
    description: String?,
    default: Boolean = true,
) : PatchBuilder<ResourcePatchContext>(name, description, default) {
    override fun build() = ResourcePatch(
        name,
        description,
        resolveDefaultValue(),
        compatibility,
        dependencies,
        options,
        executeBlock,
        finalizeBlock,
        availability,
        category,
    )
}

/**
 * Create a new [ResourcePatch].
 *
 * @param name The name of the patch.
 * If null, the patch is named "Patch" and will not be loaded by [PatchLoader].
 * @param description The description of the patch.
 * @param default Whether the patch is enabled by default.
 * @param block The block to build the patch.
 *
 * @return The created [ResourcePatch].
 */
fun resourcePatch(
    name: String? = null,
    description: String? = null,
    default: Boolean = true,
    block: ResourcePatchBuilder.() -> Unit = {},
) = ResourcePatchBuilder(name, description, default).buildPatch(block) as ResourcePatch

/**
 * An exception thrown when patching.
 *
 * @param errorMessage The exception message.
 * @param cause The corresponding [Throwable].
 */
class PatchException(errorMessage: String?, cause: Throwable?) : Exception(errorMessage, cause) {
    constructor(errorMessage: String) : this(errorMessage, null)
    constructor(cause: Throwable) : this(cause.message, cause)
}

/**
 * A result of executing a [Patch].
 *
 * @param patch The [Patch] that was executed.
 * @param exception The [PatchException] thrown, if any.
 */
class PatchResult internal constructor(val patch: Patch<*>, val exception: PatchException? = null)

/**
 * A loader for patches.
 *
 * Loads unnamed patches from JAR or DEX files declared as public static fields
 * or returned by public static and non-parametrized methods.
 *
 * @param byPatchesFile The patches associated by the patches file they were loaded from.
 */
@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
sealed class PatchLoader(
    val byPatchesFile: Map<File, Set<Patch<*>>>,
) : Set<Patch<*>> by byPatchesFile.values.flatten().toSet() {
    /**
     * @param patchesFiles A set of JAR or DEX files to load the patches from.
     * @param getBinaryClassNames A function that returns the binary names of all classes accessible by the class loader.
     * @param classLoader The [ClassLoader] to use for loading the classes.
     */
    constructor(
        patchesFiles: Set<File>,
        getBinaryClassNames: (patchesFile: File) -> List<String>,
        classLoader: ClassLoader,
    ) : this(classLoader.loadPatches(patchesFiles.associateWith { getBinaryClassNames(it).toSet() }))

    /**
     * A [PatchLoader] for JAR files.
     *
     * @param patchesFiles The JAR files to load the patches from.
     *
     * @constructor Create a new [PatchLoader] for JAR files.
     */
    class Jar(patchesFiles: Set<File>) :
        PatchLoader(
            patchesFiles,
            { file ->
                JarFile(file).entries().toList().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }
                    .map { it.name.substringBeforeLast('.').replace('/', '.') }
            },
            URLClassLoader(patchesFiles.map { it.toURI().toURL() }.toTypedArray()),
        )

    /**
     * A [PatchLoader] for [Dex] files.
     *
     * @param patchesFiles The DEX files to load the patches from.
     * @param optimizedDexDirectory The directory to store optimized DEX files in.
     * This parameter is deprecated and has no effect since API level 26.
     *
     * @constructor Create a new [PatchLoader] for [Dex] files.
     */
    class Dex(patchesFiles: Set<File>, optimizedDexDirectory: File? = null) :
        PatchLoader(
            patchesFiles,
            { patchBundle ->
                val tempDir = Files.createTempDirectory("morphe-extracted-patches").toFile()
                try {
                    DexReadWrite.readMultidexFileFromZip(patchBundle, tempDir).use { readResult ->
                        readResult.dexFile.classes
                            .map { classDef ->
                                classDef.type.substring(1, classDef.length - 1)
                            }
                    }
                } finally {
                    // The extracted DEX only serves to list class names, and the class loader
                    // reads the bundle itself, so nothing needs it once the mappings are closed
                    tempDir.deleteRecursively()
                }
            },
            DexClassLoader(
                patchesFiles.joinToString(File.pathSeparator) { it.absolutePath },
                optimizedDexDirectory?.absolutePath,
                null,
                this::class.java.classLoader,
            ),
        )

    // Companion object required for unit tests.
    private companion object {
        private val logger = Logger.getLogger(PatchLoader::class.java.name)

        val Class<*>.isPatch get() = Patch::class.java.isAssignableFrom(this)

        /**
         * Public static fields that are patches.
         */
        private val Class<*>.patchFields
            get() = fields.filter { field ->
                field.type.isPatch && field.canAccess()
            }.map { field ->
                field.get(null) as Patch<*>
            }

        /**
         * Public static and non-parametrized methods that return patches.
         */
        private val Class<*>.patchMethods
            get() = methods.filter { method ->
                method.returnType.isPatch && method.parameterCount == 0 && method.canAccess()
            }.map { method ->
                method.invoke(null) as Patch<*>
            }

        /**
         * Loads unnamed patches declared as public static fields
         * or returned by public static and non-parametrized methods.
         * Each patch remembers the patch bundle it was loaded from, see [Patch.bundle],
         * and so do its dependencies that are not loaded themselves, such as unnamed patches.
         *
         * @param binaryClassNamesByPatchesFile The binary class name of the classes to load the patches from
         * associated by the patches file.
         *
         * @return The loaded patches associated by the patches file.
         */
        private fun ClassLoader.loadPatches(binaryClassNamesByPatchesFile: Map<File, Set<String>>) =
            binaryClassNamesByPatchesFile.mapValues { (patchesFile, binaryClassNames) ->
                val bundle = PatchBundleInfo.read(patchesFile)

                binaryClassNames.asSequence().mapNotNull {
                    try {
                        loadClass(it)
                    } catch (e: NoClassDefFoundError) {
                        // Can happen under certain conditions, like loading classes with no base version.
                        // Should be caught by filtering out classes under META-INF, but check again here
                        // just in case.
                        null
                    }
                }.flatMap {
                    it.patchFields + it.patchMethods
                }.filter {
                    it.name != null
                }.onEach {
                    it.bundle = bundle
                    it.assignBundleToDependencies(bundle)
                }.toSet()
            }.also { checkUniversalPatchDefaults(it.values.flatten()) }

        /**
         * Fails if universal patches are declared with default true and
         * [PatcherBehavior.STRICT_UNIVERSAL_PATCH_DEFAULT] applies to them.
         * Otherwise they are warned about and load with default false.
         */
        private fun checkUniversalPatchDefaults(patches: Collection<Patch<*>>) {
            val misdeclared = mutableSetOf<Patch<*>>()
            patches.forEachRecursively { if (it.declaredUniversalWithDefault) misdeclared += it }
            if (misdeclared.isEmpty()) return

            val behavior = PatcherBehavior.STRICT_UNIVERSAL_PATCH_DEFAULT
            val fails = behavior in resolvePatcherBehaviors(null, misdeclared, logger, listOf(behavior))

            val message = buildString {
                append("Universal patches must be declared with default false")
                if (!fails) append(", so they are loaded with default false")
                appendLine(":")
                misdeclared.groupBy { it.bundle }.forEach { (bundle, patches) ->
                    appendLine("  ${bundle ?: "Unknown patch bundle"}${bundle?.let { " (${it.file})" } ?: ""}")
                    patches.forEach { patch ->
                        appendLine("    - \"$patch\"${patch.declaredAt?.let { " declared at $it" } ?: ""}")
                    }
                }
            }.trimEnd()

            if (fails) throw PatchException(message)
            logger.warning(message)
        }

        /**
         * Dependencies are built with the patch that depends on them, unless they are loaded from
         * a bundle themselves. Otherwise they would count as built for an unknown version.
         */
        private fun Patch<*>.assignBundleToDependencies(bundle: PatchBundleInfo) {
            dependencies.forEach { dependency ->
                if (dependency.name == null || dependency.bundle == null) {
                    if (dependency.bundle == bundle) return@forEach
                    dependency.bundle = bundle
                    dependency.assignBundleToDependencies(bundle)
                }
            }
        }

        private fun Member.canAccess(): Boolean {
            if (this is Method && parameterCount != 0) return false

            return Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers)
        }
    }
}

/**
 * Loads patches from JAR files declared as public static fields
 * or returned by public static and non-parametrized methods.
 * Patches with no name are not loaded.
 *
 * @param patchesFiles The JAR files to load the patches from.
 *
 * @return The loaded patches.
 */
fun loadPatchesFromJar(patchesFiles: Set<File>) =
    PatchLoader.Jar(patchesFiles)

/**
 * Loads patches from DEX files declared as public static fields
 * or returned by public static and non-parametrized methods.
 * Patches with no name are not loaded.
 *
 * @param patchesFiles The DEX files to load the patches from.
 *
 * @return The loaded patches.
 */
fun loadPatchesFromDex(patchesFiles: Set<File>, optimizedDexDirectory: File? = null) =
    PatchLoader.Dex(patchesFiles, optimizedDexDirectory)
