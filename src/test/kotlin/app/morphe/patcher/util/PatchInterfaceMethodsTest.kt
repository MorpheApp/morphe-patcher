/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.util

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatchInterfaceMethodsTest {

    private companion object {
        const val INTERFACE = "Lapp/morphe/extension/Patch\$Access;"
        const val TARGET = "Lapp/Target;"
    }

    private fun assemble(smali: String): ClassDef {
        val lexer = smaliFlexLexer(StringReader(smali.trimIndent()), 34)
        val tokens = CommonTokenStream(lexer)
        val parser = smaliParser(tokens)
        val tree = parser.smali_file().tree
        check(lexer.numberOfSyntaxErrors == 0 && parser.numberOfSyntaxErrors == 0) {
            "Invalid test smali:\n$smali"
        }

        val nodes = CommonTreeNodeStream(tree)
        nodes.tokenStream = tokens
        val walker = smaliTreeWalker(nodes)
        walker.setDexBuilder(DexBuilder(Opcodes.getDefault()))

        return walker.smali_file()
    }

    private val interfaceClass = assemble(
        """
            .class public interface abstract $INTERFACE
            .super Ljava/lang/Object;
            .method public abstract patch_seek(JLjava/lang/Object;)Z
            .end method
            .method public abstract patch_stop()V
            .end method
            .method public abstract patch_getName()Ljava/lang/String;
            .end method
            .method public abstract patch_getTime()J
            .end method
            .method public abstract patch_setTime(J)V
            .end method
            .method public abstract patch_setListener(Ljava/lang/Object;)V
            .end method
            .method public abstract patch_getShared()Ljava/lang/Object;
            .end method
            .method public abstract patch_many(IIIIII)V
            .end method
            .method public abstract patch_overloaded(I)V
            .end method
            .method public abstract patch_overloaded(Ljava/lang/String;)V
            .end method
        """
    )

    private val childInterfaceClass = assemble(
        """
            .class public interface abstract Lapp/morphe/extension/Patch${'$'}ChildAccess;
            .super Ljava/lang/Object;
            .implements $INTERFACE
            .method public abstract patch_child()V
            .end method
        """
    )

    private val parentClass = assemble(
        """
            .class public Lapp/Parent;
            .super Landroid/app/Activity;
            .field public time:J
        """
    )

    private val unrelatedClass = assemble(
        """
            .class public Lapp/Unrelated;
            .super Ljava/lang/Object;
            .field public name:Ljava/lang/String;
            .field public static shared:Lapp/Target;
        """
    )

    private fun targetClass(modifiers: String = "public") = MutableClass(
        assemble(
            """
                .class $modifiers $TARGET
                .super Lapp/Parent;
                .field private a:Ljava/lang/Object;
                .field private b:Lapp/Listener;
                .method public a(JLapp/Source;)Z
                    .registers 5
                    const/4 v0, 0x0
                    return v0
                .end method
                .method private b()V
                    .registers 1
                    return-void
                .end method
                .method public static c(IIIIII)V
                    .registers 6
                    return-void
                .end method
            """
        )
    )

    private fun resolver(target: MutableClass): ClassResolver {
        val classes = listOf(interfaceClass, childInterfaceClass, parentClass, unrelatedClass, target).associateBy { it.type }
        return { type -> classes[type] }
    }

    private fun MutableClass.method(name: String) = methods.single { it.name == name }

    private fun MutableMethod.opcodes() = instructions.map { it.opcode }

    private fun targetReference(name: String, parameters: List<String>, returnType: String) =
        ImmutableMethodReference(TARGET, name, parameters, returnType)

    @Test
    fun `smali method takes its signature from the interface`() {
        val target = targetClass()

        val method = target.addPatchInterfaceMethod(
            resolver(target),
            INTERFACE,
            "patch_seek",
            """
                const/4 v0, 0x1
                return v0
            """,
            null,
        )

        assertEquals(listOf(INTERFACE), target.interfaces)
        assertEquals(listOf("J", "Ljava/lang/Object;"), method.parameterTypes.map { it.toString() })
        assertEquals("Z", method.returnType)
        assertEquals(AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, method.accessFlags)
        // v0, a spare register for a wide value, then p0, wide p1 and p3.
        assertEquals(6, method.implementation!!.registerCount)
        assertTrue(target.methods.contains(method))
    }

    @Test
    fun `interface can be a class name`() {
        val target = targetClass()

        target.addPatchInterfaceMethod(
            resolver(target),
            "app.morphe.extension.Patch\$Access",
            "patch_stop",
            "return-void",
            null,
        )

        assertEquals(listOf(INTERFACE), target.interfaces)
    }

    @Test
    fun `method of an extended interface adds the given interface`() {
        val target = targetClass()
        val childInterface = "Lapp/morphe/extension/Patch${'$'}ChildAccess;"

        val method = target.addPatchInterfaceMethod(resolver(target), childInterface, "patch_stop", "return-void", null)

        assertEquals("patch_stop", method.name)
        assertEquals(listOf(childInterface), target.interfaces)
    }

    @Test
    fun `interface is added only once`() {
        val target = targetClass()
        val resolver = resolver(target)

        target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_stop", "return-void", null)
        target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_setTime", "return-void", null)

        assertEquals(listOf(INTERFACE), target.interfaces)
    }

    @Test
    fun `smali method resolves parameter registers and labels`() {
        val target = targetClass()

        val method = target.addPatchInterfaceMethod(
            resolver(target),
            INTERFACE,
            "patch_seek",
            """
                if-eqz p3, :no_source
                const/4 v0, 0x1
                return v0
                :no_source
                const/4 v0, 0x0
                return v0
            """,
            null,
        )

        // Registers: v0, v1, p0 = v2, p1 = v3 and v4, p3 = v5.
        val branch = method.instructions.first() as OneRegisterInstruction
        assertEquals(Opcode.IF_EQZ, branch.opcode)
        assertEquals(5, branch.registerA)
    }

    @Test
    fun `smali method without local registers only has parameter registers`() {
        val target = targetClass()

        val method = target.addPatchInterfaceMethod(resolver(target), INTERFACE, "patch_stop", "return-void", null)

        assertEquals(1, method.implementation!!.registerCount)
    }

    @Test
    fun `member names that look like registers are not counted`() {
        assertEquals(
            2,
            localRegisterCount(
                """
                    const-string v0, "v30 and v40"
                    invoke-virtual { p0 }, Lv50;->v60()V
                    iget v0, p0, Lv70;->v80:I
                    goto :v90
                    :v90
                    return-void
                """
            )
        )
        assertEquals(0, localRegisterCount("return-void"))
        assertEquals(7, localRegisterCount("invoke-static/range { v2 .. v5 }, La;->a(IIII)V"))
    }

    @Test
    fun `overloaded interface method needs parameters`() {
        val target = targetClass()
        val resolver = resolver(target)

        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_overloaded", "return-void", null)
        }

        val method = target.addPatchInterfaceMethod(
            resolver, INTERFACE, "patch_overloaded", "return-void", listOf("Ljava/lang/String;")
        )
        assertEquals(listOf("Ljava/lang/String;"), method.parameterTypes.map { it.toString() })
    }

    @Test
    fun `missing interface or method fails`() {
        val target = targetClass()
        val resolver = resolver(target)

        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, "Lapp/Missing;", "patch_stop", "return-void", null)
        }
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_missing", "return-void", null)
        }
        // Not an interface.
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, "Lapp/Parent;", "patch_stop", "return-void", null)
        }
        assertTrue(target.interfaces.isEmpty())
    }

    @Test
    fun `adding the same method twice fails`() {
        val target = targetClass()
        val resolver = resolver(target)

        target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_stop", "return-void", null)
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_stop", "return-void", null)
        }
    }

    @Test
    fun `invalid smali fails and adds nothing`() {
        val target = targetClass()
        val methodCount = target.methods.size

        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver(target), INTERFACE, "patch_stop", "not-an-instruction v0", null)
        }
        assertTrue(target.interfaces.isEmpty())
        assertEquals(methodCount, target.methods.size)
    }

    @Test
    fun `method of an interface class is not final`() {
        // The methods of the target are not valid for an interface, but only the class flags matter.
        val target = targetClass("public interface abstract")
        val method = target.addPatchInterfaceMethod(resolver(target), INTERFACE, "patch_stop", "return-void", null)

        assertEquals(AccessFlags.PUBLIC.value, method.accessFlags)
    }

    @Test
    fun `method reference is called with casts`() {
        val target = targetClass()

        val method = target.addPatchInterfaceMethod(
            resolver(target),
            INTERFACE,
            "patch_seek",
            targetReference("a", listOf("J", "Lapp/Source;"), "Z"),
            null,
        )

        assertEquals(
            listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.RETURN),
            method.opcodes(),
        )
        // v0, then p0 = v1, p1 = v2 and v3, p3 = v4.
        assertEquals(5, method.implementation!!.registerCount)
        assertEquals(4, (method.instructions[0] as OneRegisterInstruction).registerA)
        val invoke = method.instructions[1] as FiveRegisterInstruction
        assertEquals(4, invoke.registerCount)
        assertEquals(listOf(1, 2, 3, 4), listOf(invoke.registerC, invoke.registerD, invoke.registerE, invoke.registerF))
    }

    @Test
    fun `private method is called directly`() {
        val target = targetClass()

        val method = target.addPatchInterfaceMethod(
            resolver(target), INTERFACE, "patch_stop", targetReference("b", listOf(), "V"), null,
        )

        assertEquals(listOf(Opcode.INVOKE_DIRECT, Opcode.RETURN_VOID), method.opcodes())
    }

    @Test
    fun `inherited framework method is called virtually`() {
        val target = targetClass()

        val method = target.addPatchInterfaceMethod(
            resolver(target),
            INTERFACE,
            "patch_stop",
            ImmutableMethodReference("Landroid/app/Activity;", "finish", listOf(), "V"),
            null,
        )

        assertEquals(listOf(Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), method.opcodes())
    }

    @Test
    fun `static method with many parameters is called with a range`() {
        val target = targetClass()

        val method = target.addPatchInterfaceMethod(
            resolver(target),
            INTERFACE,
            "patch_many",
            target.method("c"),
            null,
        )

        assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.RETURN_VOID), method.opcodes())
        val invoke = method.instructions[0] as RegisterRangeInstruction
        // p0 is not passed to a static method.
        assertEquals(1, invoke.startRegister)
        assertEquals(6, invoke.registerCount)
    }

    @Test
    fun `void interface method ignores the return value`() {
        val target = targetClass()

        val method = target.addPatchInterfaceMethod(
            resolver(target),
            INTERFACE,
            "patch_setTime",
            ImmutableMethodReference(TARGET, "d", listOf("J"), "I"),
            null,
        )

        assertEquals(listOf(Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), method.opcodes())
    }

    @Test
    fun `method reference that does not fit the interface method fails`() {
        val target = targetClass()
        val resolver = resolver(target)

        // Parameter count.
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_stop", targetReference("d", listOf("I"), "V"), null)
        }
        // Primitive parameter type.
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_setTime", targetReference("d", listOf("I"), "V"), null)
        }
        // Primitive return type.
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_getTime", targetReference("d", listOf(), "I"), null)
        }
        // Method of a class that the target does not extend.
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(
                resolver,
                INTERFACE,
                "patch_stop",
                ImmutableMethodReference("Lapp/Unrelated;", "d", listOf(), "V"),
                null,
            )
        }
        // Constructor.
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_stop", targetReference("<init>", listOf(), "V"), null)
        }
        assertTrue(target.interfaces.isEmpty())
    }

    @Test
    fun `wide field of a parent class has a getter and a setter`() {
        val target = targetClass()
        val resolver = resolver(target)
        val field = ImmutableFieldReference("Lapp/Parent;", "time", "J")

        val getter = target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_getTime", field, null)
        assertEquals(listOf(Opcode.IGET_WIDE, Opcode.RETURN_WIDE), getter.opcodes())
        assertEquals(3, getter.implementation!!.registerCount)

        val setter = target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_setTime", field, null)
        assertEquals(listOf(Opcode.IPUT_WIDE, Opcode.RETURN_VOID), setter.opcodes())
        val put = setter.instructions[0] as TwoRegisterInstruction
        assertEquals(1, put.registerA)
        assertEquals(0, put.registerB)
    }

    @Test
    fun `object field setter casts to the field type`() {
        val target = targetClass()
        val resolver = resolver(target)

        val cast = target.addPatchInterfaceMethod(
            resolver, INTERFACE, "patch_setListener", target.fields.single { it.name == "b" }, null
        )
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID), cast.opcodes())
        assertEquals(
            "Lapp/Listener;",
            ((cast.instructions[0] as ReferenceInstruction).reference).toString(),
        )
    }

    @Test
    fun `object field of type object needs no cast`() {
        val target = targetClass()

        val setter = target.addPatchInterfaceMethod(
            resolver(target), INTERFACE, "patch_setListener", target.fields.single { it.name == "a" }, null
        )

        assertEquals(listOf(Opcode.IPUT_OBJECT, Opcode.RETURN_VOID), setter.opcodes())
    }

    @Test
    fun `static field of another class has a getter`() {
        val target = targetClass()

        val getter = target.addPatchInterfaceMethod(
            resolver(target),
            INTERFACE,
            "patch_getShared",
            ImmutableFieldReference("Lapp/Unrelated;", "shared", "Lapp/Target;"),
            null,
        )

        assertEquals(listOf(Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT), getter.opcodes())
    }

    @Test
    fun `field that does not fit the interface method fails`() {
        val target = targetClass()
        val resolver = resolver(target)
        val time = ImmutableFieldReference("Lapp/Parent;", "time", "J")

        // Neither a getter nor a setter.
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_stop", time, null)
        }
        // Primitive type differs from the object type.
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(resolver, INTERFACE, "patch_getName", time, null)
        }
        // Instance field of a class that the target does not extend.
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(
                resolver,
                INTERFACE,
                "patch_getName",
                ImmutableFieldReference("Lapp/Unrelated;", "name", "Ljava/lang/String;"),
                null,
            )
        }
        // Unknown field.
        assertFailsWith<PatchException> {
            target.addPatchInterfaceMethod(
                resolver, INTERFACE, "patch_getTime", ImmutableFieldReference(TARGET, "missing", "J"), null
            )
        }
        assertTrue(target.interfaces.isEmpty())
    }
}
