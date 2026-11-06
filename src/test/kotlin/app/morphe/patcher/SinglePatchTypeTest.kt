/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

@file:Suppress("DEPRECATION")

package app.morphe.patcher

import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodeContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.decode
import app.morphe.patcher.patch.deleteResource
import app.morphe.patcher.patch.document
import app.morphe.patcher.patch.fileWorkspace
import app.morphe.patcher.patch.listApkEntries
import app.morphe.patcher.patch.listDecodableFiles
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.resourceContext
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceIds
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.validate
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.coder.FULL_RESOURCE_ENCODE_PROPERTY
import app.morphe.patcher.resource.coder.ResourceTestApk
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.function.Supplier
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Patches declared with [patch], which decode what they use when they first need it, patched with [Patcher]
 * and compared to the deprecated patch types, which declare what they use.
 */
internal class SinglePatchTypeTest {
    @TempDir
    lateinit var tempDir: File

    private val inputApk by lazy { inputApk() }

    /**
     * What a patched APK holds, and how it was patched.
     *
     * @param entries The CRC of each entry.
     */
    private class Patched(
        val entries: Map<String, Long>,
        val resourceMode: ResourceMode,
        val bytecodeMode: BytecodeMode,
    ) {
        /** The entries that are not as in [other], and the ones [other] does not have. */
        fun changedFrom(other: Patched) = entries.filter { (name, crc) -> other.entries[name] != crc }.keys
    }

    private fun Patched.assertSameAs(other: Patched) = assertEquals(other.entries, entries)

    private fun entriesOf(apk: File) = ZipFile(apk).use { zip ->
        zip.entries().asSequence().associate { it.name to it.crc }
    }

    private val input by lazy { Patched(entriesOf(inputApk), ResourceMode.NONE, BytecodeMode.NONE) }

    private var runs = 0

    /**
     * @param targetPatcherVersion The version whose behaviors apply. Patches without a bundle
     * otherwise count as built for the oldest version, which decodes every resource up front.
     */
    private fun patched(
        vararg patches: Patch,
        targetPatcherVersion: String? = "2.2.0",
        validate: Boolean = false,
    ): Patched {
        val name = "run${runs++}"
        val config = PatcherConfig(
            inputApk,
            tempDir.resolve(name),
            targetPatcherVersion = targetPatcherVersion,
            validate = validate,
        )
        val output = tempDir.resolve("$name.apk")
        Patcher(config).use { patcher ->
            patcher += patches.toSet()
            runBlocking { patcher().toList() }.forEach { assertNull(it.exception, it.exception?.stackTraceToString()) }
            patcher.writeApk(output)
        }
        return Patched(entriesOf(output), config.resourceMode, config.bytecodeMode)
    }

    private fun changeClass() {
        mutableClassDefBy(MAIN_CLASS).accessFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
    }

    private fun changeResource() = document("res/values/strings.xml").use { document ->
        val strings = document.getElementsByTagName("string")
        for (index in 0 until strings.length) {
            val string = strings.item(index)
            if (string.attributes.getNamedItem("name").nodeValue == "hello") string.textContent = "Patched"
        }
    }

    private fun changeEntries() {
        resourceFile("assets/read.txt").readText()
        resourceFile("assets/edit.txt").writeText("edited")
        resourceFile("assets/added.txt", false).writeText("added")
        deleteResource("assets/delete.txt")
    }

    // region What is decoded.

    @Test
    fun `a patch that uses nothing decodes nothing`() {
        val patched = patched(patch("Nothing") { execute { } })

        assertEquals(ResourceMode.NONE, patched.resourceMode)
        assertEquals(BytecodeMode.NONE, patched.bytecodeMode)
        assertEquals(input.entries, patched.entries)
    }

    @Test
    fun `a patch that uses classes keeps the resources as they are`() {
        val patched = patched(patch("Classes") { execute { changeClass() } })

        assertEquals(ResourceMode.NONE, patched.resourceMode)
        assertEquals(BytecodeMode.STRIP_FAST, patched.bytecodeMode)
        assertEquals(setOf("classes2.dex"), patched.changedFrom(input) - "classes.dex")
        patched.assertSameAs(patched(bytecodePatch("Classes") { execute { changeClass() } }))
    }

    @Test
    fun `a patch that uses decoded resources keeps the DEX files as they are`() {
        val patched = patched(patch("Resources") { execute { changeResource() } })

        assertEquals(ResourceMode.FULL, patched.resourceMode)
        assertEquals(BytecodeMode.NONE, patched.bytecodeMode)
        assertFalse(patched.changedFrom(input).any { it.endsWith(".dex") })
        patched.assertSameAs(patched(resourcePatch("Resources") { execute { changeResource() } }))
    }

    @Test
    fun `a patch that uses entries of the APK keeps the manifest and the resource table as they are`() {
        val patched = patched(patch("Entries") { execute { changeEntries() } })

        assertEquals(ResourceMode.RAW_ONLY, patched.resourceMode)
        assertEquals(BytecodeMode.NONE, patched.bytecodeMode)
        assertEquals(setOf("assets/edit.txt", "assets/added.txt"), patched.changedFrom(input))
        assertFalse("assets/delete.txt" in patched.entries)
        patched.assertSameAs(patched(rawResourcePatch("Entries") { execute { changeEntries() } }))
    }

    @Test
    fun `listing entries of the APK decodes no resources`() {
        val patched = patched(
            patch("List") {
                execute {
                    assertTrue("assets/read.txt" in listApkEntries("assets/"))
                    assertEquals(listOf("assets/read.txt"), listDecodableFiles("assets/read*"))
                    assertEquals(listOf("assets/read.txt"), decode("assets/read*"))
                }
            },
        )

        assertEquals(ResourceMode.RAW_ONLY, patched.resourceMode)
    }

    @Test
    fun `listing decodable resources decodes resources`() {
        val patched = patched(
            patch("List") {
                execute { assertTrue("res/values/strings.xml" in listDecodableFiles("res/values/*")) }
            },
        )

        assertEquals(ResourceMode.FULL, patched.resourceMode)
    }

    @Test
    fun `resource ids and the package need nothing decoded`() {
        val patched = patched(
            patch("Metadata") {
                execute {
                    assertEquals("com.test.app", packageMetadata.packageName)
                    assertTrue(resourceIds.has(ResourceType.STRING, "hello"))
                }
            },
        )

        assertEquals(ResourceMode.NONE, patched.resourceMode)
        assertEquals(BytecodeMode.NONE, patched.bytecodeMode)
    }

    @Test
    fun `validating does not decode what patches do not use`() {
        val patched = patched(patch("Resources") { execute { changeResource() } }, validate = true)

        assertEquals(BytecodeMode.NONE, patched.bytecodeMode)
    }

    @Test
    fun `the order of the first use does not change the patched APK`() {
        val classesFirst = patched(patch("Both") { execute { changeClass(); changeResource() } })
        val resourcesFirst = patched(patch("Both") { execute { changeResource(); changeClass() } })
        val deprecated = patched(
            bytecodePatch("Both") {
                dependsOn(resourcePatch { execute { changeResource() } })
                execute { changeClass() }
            },
        )

        assertEquals(deprecated.entries, classesFirst.entries)
        assertEquals(deprecated.entries, resourcesFirst.entries)
    }

    @ParameterizedTest
    @ValueSource(strings = ["on request", "up front", "full rebuild"])
    fun `what patches staged before resources are decoded is kept`(decoding: String) {
        val staged = {
            val targetPatcherVersion = if (decoding == "up front") null else "2.2.0"
            val entriesFirst = patched(
                patch("Both") { execute { changeEntries(); changeResource() } },
                targetPatcherVersion = targetPatcherVersion,
            )
            val resourcesFirst = patched(
                patch("Both") { execute { changeResource(); changeEntries() } },
                targetPatcherVersion = targetPatcherVersion,
            )
            val deprecated = patched(
                resourcePatch("Both") { execute { changeEntries(); changeResource() } },
                targetPatcherVersion = targetPatcherVersion,
            )

            assertTrue("assets/added.txt" in entriesFirst.entries)
            assertFalse("assets/delete.txt" in entriesFirst.entries)
            assertEquals(input.entries["assets/read.txt"], entriesFirst.entries["assets/read.txt"])
            assertEquals(deprecated.entries, entriesFirst.entries)
            assertEquals(deprecated.entries, resourcesFirst.entries)
        }

        if (decoding == "full rebuild") {
            System.setProperty(FULL_RESOURCE_ENCODE_PROPERTY, "true")
            try {
                staged()
            } finally {
                System.clearProperty(FULL_RESOURCE_ENCODE_PROPERTY)
            }
        } else {
            staged()
        }
    }

    @Test
    fun `the deprecated patch types decode what they declare before patches execute`() {
        var bytecodeDecoded: Boolean? = null
        var resourcesDecoded: Boolean? = null

        patched(
            bytecodePatch("Bytecode") {
                execute {
                    val context = PatcherContext.current!!
                    bytecodeDecoded = !context.bytecodeContext.decodeOnFirstUse
                    resourcesDecoded = !context.resourceContext.decodeOnFirstUse
                }
            },
        )
        assertEquals(true, bytecodeDecoded)
        assertEquals(false, resourcesDecoded)

        patched(
            resourcePatch("Resources") {
                execute {
                    val context = PatcherContext.current!!
                    bytecodeDecoded = !context.bytecodeContext.decodeOnFirstUse
                    resourcesDecoded = !context.resourceContext.decodeOnFirstUse
                }
            },
        )
        assertEquals(false, bytecodeDecoded)
        assertEquals(true, resourcesDecoded)
    }

    @Test
    fun `a deprecated patch type can use what it did not declare with the global functions`() {
        val bytecodeUsesResources = patched(
            bytecodePatch("Both") { execute { changeClass(); changeResource() } },
        )
        val resourcesUseBytecode = patched(
            resourcePatch("Both") { execute { changeResource(); changeClass() } },
        )

        assertEquals(bytecodeUsesResources.entries, resourcesUseBytecode.entries)
        assertEquals(ResourceMode.FULL, bytecodeUsesResources.resourceMode)
        assertEquals(BytecodeMode.STRIP_FAST, resourcesUseBytecode.bytecodeMode)
    }

    @Test
    fun `resources are decoded up front if a raw resource patch is applied with a patch that can need them`() {
        val patched = patched(
            rawResourcePatch("Raw") { execute { resourceFile("assets/added.txt", false).writeText("added") } },
            patch("Resources") { execute { changeResource() } },
        )

        assertEquals(ResourceMode.FULL, patched.resourceMode)
        assertTrue("assets/added.txt" in patched.entries)
    }

    // endregion

    // region Patches.

    @Test
    fun `patch creates a patch of the parent class`() {
        assertSame(Patch::class.java, patch("Patch") { }.javaClass)
    }

    @Test
    fun `dependencies execute first and finalize blocks execute last in reverse order`() {
        val executed = mutableListOf<String>()

        patched(
            patch("Dependent") {
                dependsOn(
                    patch { execute { executed += "1" } },
                    bytecodePatch {
                        execute { executed += "2" }
                        finalize { executed += "-2" }
                    },
                    patch {
                        execute { executed += "3" }
                        finalize { executed += "-3" }
                    },
                )
                execute { executed += "4" }
                finalize { executed += "-4" }
            },
        )

        assertEquals(listOf("1", "2", "3", "4", "-4", "-3", "-2"), executed)
    }

    @Test
    fun `a patch without a finalize block has none`() {
        assertNull(patch("Patch") { execute { } }.finalizeBlock)
        assertNotNull(patch("Patch") { finalize { } }.finalizeBlock)
    }

    @Test
    fun `an unnamed patch goes by the file that declares its blocks`() {
        assertEquals("SinglePatchTypeTest", patch { execute { } }.toString())
        assertEquals("SinglePatchTypeTest", patch { finalize { } }.toString())
        assertTrue(patch { }.toString().startsWith("Patch@"))
    }

    @Test
    fun `extensions are merged before the patch executes`() {
        val extension = dexOf(ImmutableClassDef("Lcom/test/Extension;", 0, null, null, null, null, null, null))
        var extended = false

        val patched = patched(
            patch("Extended") {
                extendWith(Supplier<InputStream> { ByteArrayInputStream(extension) })
                execute { extended = classDefByOrNull("Lcom/test/Extension;") != null }
            },
        )

        assertTrue(extended)
        assertEquals(BytecodeMode.STRIP_FAST, patched.bytecodeMode)
    }

    @Test
    fun `extensions are coalesced like the extensions of a bytecode patch`() {
        val derivedExtensions = mutableListOf<Supplier<InputStream>>()
        val eagerExtension = Supplier<InputStream> { ByteArrayInputStream(ByteArray(0)) }

        val patch = patch(name = "Test") {
            extendWithAll { derivedExtensions }
            extendWith(eagerExtension)
        }
        derivedExtensions += Supplier<InputStream> { ByteArrayInputStream(ByteArray(0)) }
        derivedExtensions += Supplier<InputStream> { ByteArrayInputStream(ByteArray(0)) }

        assertEquals(listOf(1, 2), patch.extensionStreamProviders.map { it.get().count() })
    }

    // endregion

    // region Global functions.

    @Test
    fun `the global functions are available anywhere while patching`() {
        val workspace = tempDir.resolve("workspace")
        var classes = 0

        Patcher(PatcherConfig(inputApk, tempDir.resolve("run"), fileWorkspacePath = workspace, validate = true)).use {
            it += setOf(patch("Patch") { execute { classDefForEach { classes++ } } })
            runBlocking { it().toList() }

            assertEquals(workspace, fileWorkspace)
            assertTrue(validate)
            assertSame(PatcherContext.current!!.bytecodeContext, bytecodeContext)
            assertSame(PatcherContext.current!!.resourceContext, resourceContext)
        }

        assertEquals(2, classes)
    }

    @Test
    fun `the global functions are not available when nothing is being patched`() {
        val fingerprint = Fingerprint(definingClass = MAIN_CLASS)

        val exception = assertFailsWith<PatchException> { classDefBy(MAIN_CLASS) }
        assertTrue("newInstance(type = {" in exception.message!!, exception.message)
        assertFailsWith<PatchException> { fingerprint.originalClassDef }
        assertFailsWith<PatchException> { document("AndroidManifest.xml") }

        Patcher(PatcherConfig(inputApk, tempDir.resolve("run"))).close()

        assertFailsWith<PatchException> { classDefBy(MAIN_CLASS) }
    }

    @Test
    fun `a filter can use another fingerprint without a context`() {
        val otherFingerprint = Fingerprint(definingClass = OTHER_CLASS)
        val fingerprint = Fingerprint(filters = listOf(newInstance(type = { otherFingerprint.originalClassDef })))
        var matchedClass: String? = null

        patched(patch("Filter") { execute { matchedClass = fingerprint.originalClassDef.type } })

        assertEquals(MAIN_CLASS, matchedClass)
    }

    @Test
    fun `method call and field access filters can use another fingerprint`() {
        val otherFingerprint = Fingerprint(definingClass = OTHER_CLASS)
        val fingerprint = Fingerprint(
            filters = listOf(
                fieldAccess(definingClass = { otherFingerprint.originalClassDef }, type = { otherFingerprint.originalClassDef }),
                methodCall(reference = { otherFingerprint.originalMethod }),
            ),
        )
        var matchedClass: String? = null

        patched(patch("Filter") { execute { matchedClass = fingerprint.originalClassDef.type } })

        assertEquals(MAIN_CLASS, matchedClass)
    }

    @Test
    fun `helpers need no context and can be declared anywhere`() {
        fun helper(): String = classDefBy(MAIN_CLASS).type

        var type: String? = null
        patched(patch("Helper") { execute { type = helper() } })

        assertEquals(MAIN_CLASS, type)
    }

    // endregion

    // region Patch bundles compiled before patches stopped having a context.

    /**
     * Calls the function as patches compiled for it do, with the context as the first parameter.
     */
    private fun Any.callCompiled(name: String, context: BytecodePatchContext): Any? =
        javaClass.getMethod(name, BytecodePatchContext::class.java).invoke(this, context)

    @Test
    fun `functions that took a context still work for compiled patches`() {
        val fingerprint = Fingerprint(definingClass = MAIN_CLASS, filters = listOf(opcode(Opcode.NEW_INSTANCE)))
        var results = emptyList<Any?>()

        patched(
            bytecodePatch("Compiled") {
                execute {
                    results = listOf(
                        "matchOrNull", "match", "matchAllOrNull", "matchAll",
                        "getOriginalClassDefOrNull", "getOriginalMethodOrNull", "getOriginalClassDef", "getOriginalMethod",
                        "getClassDefOrNull", "getMethodOrNull", "getClassDef", "getMethod",
                        "getInstructionMatchesOrNull", "getInstructionMatches",
                    ).map { name -> fingerprint.callCompiled(name, this) }
                }
            },
        )

        results.forEach { assertNotNull(it) }
    }

    @Test
    fun `patch functions keep the types compiled patches refer to`() {
        val patchKt = Class.forName("app.morphe.patcher.patch.PatchKt")
        val stringType = String::class.java
        val blockType = Function1::class.java

        listOf("bytecodePatch" to "BytecodePatch", "resourcePatch" to "ResourcePatch", "rawResourcePatch" to "RawResourcePatch")
            .forEach { (name, type) ->
                val function = patchKt.getMethod(name, stringType, stringType, Boolean::class.java, blockType)
                assertEquals("app.morphe.patcher.patch.$type", function.returnType.name)
            }
    }

    // endregion

    /**
     * An APK with resources, and classes in two DEX files so that one of them can stay as it is.
     */
    private fun inputApk(): File {
        val apk = ResourceTestApk.build(tempDir.resolve("input").apply { mkdirs() })

        val main = ImmutableClassDef(
            MAIN_CLASS, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    MAIN_CLASS, "main", null, "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(
                        1,
                        listOf(
                            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(OTHER_CLASS)),
                            ImmutableInstruction21c(
                                Opcode.SGET_OBJECT, 0, ImmutableFieldReference(OTHER_CLASS, "field", OTHER_CLASS),
                            ),
                            ImmutableInstruction35c(
                                Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                                ImmutableMethodReference(OTHER_CLASS, "other", null, "V"),
                            ),
                            ImmutableInstruction10x(Opcode.RETURN_VOID),
                        ),
                        null,
                        null,
                    ),
                ),
            ),
        )
        val other = ImmutableClassDef(
            OTHER_CLASS, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    OTHER_CLASS, "other", null, "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(
                        0, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null,
                    ),
                ),
            ),
        )

        ZFile.openReadWrite(apk).use { zFile ->
            fun add(name: String, bytes: ByteArray) = zFile.add(name, ByteArrayInputStream(bytes))

            add("classes.dex", dexOf(main))
            add("classes2.dex", dexOf(other))
            add("assets/read.txt", "read".toByteArray())
            add("assets/edit.txt", "edit".toByteArray())
            add("assets/delete.txt", "delete".toByteArray())
        }

        return apk
    }

    private fun dexOf(vararg classes: ClassDef): ByteArray {
        val file = tempDir.resolve("input/${classes.first().type.trim('L', ';').replace('/', '_')}.dex")
        file.parentFile.mkdirs()
        DexPool(Opcodes.getDefault()).apply {
            classes.forEach(::internClass)
            writeTo(FileDataStore(file))
        }
        return file.readBytes()
    }

    private companion object {
        private const val MAIN_CLASS = "Lcom/test/Main;"
        private const val OTHER_CLASS = "Lcom/test/Other;"
    }
}
