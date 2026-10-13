/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

package app.morphe.patcher.util

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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
import kotlin.test.assertSame

class BytecodeUtilsTest {

    /**
     * Assembles a class with a single method from smali.
     */
    private fun mutableClass(
        registers: Int,
        body: String,
        modifiers: String = "public",
        parameters: String = "",
        returnType: String = "V",
    ): MutableClass {
        val smali = """
            .class public LTest;
            .super Ljava/lang/Object;
            .method $modifiers test($parameters)$returnType
                .registers $registers
                $body
            .end method
        """.trimIndent()

        val lexer = smaliFlexLexer(StringReader(smali), 34)
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

        return MutableClass(walker.smali_file())
    }

    private fun MutableMethod.assertMove(index: Int, opcode: Opcode, destination: Int, source: Int) {
        val instruction = getInstruction<TwoRegisterInstruction>(index)
        assertEquals(opcode, instruction.opcode)
        assertEquals(destination, instruction.registerA)
        assertEquals(source, instruction.registerB)
    }

    @Test
    fun `copies parameter registers of instance method`() {
        val mutableClass = mutableClass(
            registers = 5,
            parameters = "JLjava/lang/String;",
            body = """
                const/4 v0, 0x0
                return-void
            """
        )
        val method = mutableClass.methods.first()

        val (clonedMethod, addedInstructions) = method.addFreeRegisters(3, mutableClass)

        // Original: v0 local, p0 = v1, p1-p2 = v2-v3 (wide), p3 = v4. Register count 5.
        // Cloned: register count 8 and p0 = v4.
        assertEquals(3, addedInstructions)
        assertEquals(8, clonedMethod.implementation!!.registerCount)
        assertEquals(method.instructions.size + 3, clonedMethod.instructions.size)
        clonedMethod.assertMove(0, Opcode.MOVE_OBJECT_FROM16, 1, 4)
        clonedMethod.assertMove(1, Opcode.MOVE_WIDE_FROM16, 2, 5)
        clonedMethod.assertMove(2, Opcode.MOVE_OBJECT_FROM16, 4, 7)
        assertEquals(Opcode.CONST_4, clonedMethod.getInstruction(3).opcode)

        assertEquals(1, mutableClass.methods.size)
        assertSame(clonedMethod, mutableClass.methods.first())
    }

    @Test
    fun `copies parameter registers when adding fewer registers than parameters`() {
        val mutableClass = mutableClass(
            registers = 4,
            modifiers = "public static",
            parameters = "IJI",
            body = """
                return-void
            """
        )
        val method = mutableClass.methods.first()

        val (clonedMethod, addedInstructions) = method.addFreeRegisters(1, mutableClass)

        assertEquals(3, addedInstructions)
        assertEquals(5, clonedMethod.implementation!!.registerCount)
        clonedMethod.assertMove(0, Opcode.MOVE_FROM16, 0, 1)
        clonedMethod.assertMove(1, Opcode.MOVE_WIDE_FROM16, 1, 2)
        clonedMethod.assertMove(2, Opcode.MOVE_FROM16, 3, 4)
    }

    @Test
    fun `adds no instructions to static method without parameters`() {
        val mutableClass = mutableClass(
            registers = 1,
            modifiers = "public static",
            body = """
                const/4 v0, 0x0
                return-void
            """
        )
        val method = mutableClass.methods.first()

        val (clonedMethod, addedInstructions) = method.addFreeRegisters(2, mutableClass)

        assertEquals(0, addedInstructions)
        assertEquals(3, clonedMethod.implementation!!.registerCount)
        assertEquals(Opcode.CONST_4, clonedMethod.getInstruction(0).opcode)
    }

    @Test
    fun `rejects zero additional registers`() {
        val mutableClass = mutableClass(
            registers = 1,
            body = "return-void"
        )

        assertFailsWith<IllegalStateException> {
            mutableClass.methods.first().addFreeRegisters(0, mutableClass)
        }
    }
}
