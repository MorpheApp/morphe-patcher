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

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreeRegisterProviderTest {

    /**
     * Assembles a static method from smali so tests can describe bytecode directly.
     */
    private fun method(
        registers: Int,
        body: String,
        parameters: String = "",
        returnType: String = "V",
    ): MutableMethod {
        val smali = """
            .class public LTest;
            .super Ljava/lang/Object;
            .field public static wide:J
            .field public static array:[I
            .method public static test($parameters)$returnType
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

        return walker.smali_file().methods.first().toMutable()
    }

    @Test
    fun `finds the register written by a plain move`() {
        val method = method(
            registers = 2,
            returnType = "I",
            body = """
                const/4 v0, 0x1
                move v1, v0
                return v1
            """
        )

        assertEquals(1, method.findFreeRegister(1))
    }

    @Test
    fun `follows a branch that is the first instruction`() {
        val method = method(
            registers = 2,
            parameters = "I",
            body = """
                if-eqz p0, :skip
                const/4 v0, 0x1
                :skip
                return-void
            """
        )

        assertEquals(0, method.findFreeRegister(0))
    }

    @Test
    fun `does not use the destination of a 2addr instruction`() {
        val method = method(
            registers = 3,
            returnType = "I",
            body = """
                const/4 v0, 0x1
                const/4 v1, 0x2
                add-int/2addr v0, v1
                return v0
            """
        )

        assertEquals(2, method.findFreeRegister(2))
    }

    @Test
    fun `every 2addr opcode is a read-modify-write opcode`() {
        Opcode.entries.filter { it.name.endsWith("/2addr") }.forEach { opcode ->
            assertTrue(opcode in FreeRegisterProvider.readModifyWriteOpcodes, "Missing: $opcode")
        }
    }

    @Test
    fun `finds the register written by a compare`() {
        val method = method(
            registers = 5,
            returnType = "I",
            body = """
                const-wide v0, 0x1
                const-wide v2, 0x2
                cmp-long v4, v0, v2
                return v4
            """
        )

        assertEquals(4, method.findFreeRegister(2))
    }

    @Test
    fun `write opcodes match the opcodes dexlib2 declares as setting a register`() {
        // check-cast writes back the same object it reads, so it never frees a register.
        val notWriteOnly = setOf(Opcode.CHECK_CAST)

        Opcode.entries.filter { !it.odexOnly() && it !in notWriteOnly }.forEach { opcode ->
            assertEquals(
                opcode.setsRegister(),
                opcode in FreeRegisterProvider.writeOpcodes,
                "Mismatch: $opcode"
            )
        }
    }

    @Test
    fun `does not use the upper register of a wide value that is read later`() {
        val method = method(
            registers = 3,
            returnType = "I",
            body = """
                const-wide v0, 0x1
                sput-wide v0, LTest;->wide:J
                const/4 v1, 0x0
                return v1
            """
        )

        assertEquals(2, method.findFreeRegister(1))
    }

    @Test
    fun `only the wide operands of an instruction use a register pair`() {
        // The shift amount v2 is an int, so v3 is not read.
        val method = method(
            registers = 4,
            returnType = "J",
            body = """
                const/4 v2, 0x1
                const-wide v0, 0x5
                shl-long v0, v0, v2
                return-wide v0
            """
        )

        assertEquals(3, method.findFreeRegister(2))
    }

    @Test
    fun `a wide write frees both registers of the pair`() {
        val method = method(
            registers = 2,
            body = """
                const-wide v0, 0x1
                sput-wide v0, LTest;->wide:J
                return-void
            """
        )

        val provider = method.getFreeRegisterProvider(0, 2)
        assertEquals(listOf(0, 1), listOf(provider.getFreeRegister(), provider.getFreeRegister()))
    }

    @Test
    fun `every opcode with a wide operand is classified`() {
        Opcode.entries.filter {
            !it.odexOnly() && listOf("wide", "long", "double").any { type -> type in it.name }
        }.forEach { opcode ->
            assertTrue(
                opcode.setsWideRegister() ||
                        opcode in FreeRegisterProvider.wideFirstRegisterReadOpcodes ||
                        opcode in FreeRegisterProvider.wideSecondRegisterOpcodes,
                "Not classified: $opcode"
            )
        }
    }

    @Test
    fun `follows a branch past a payload in the middle of the method`() {
        val method = method(
            registers = 2,
            returnType = "I",
            body = """
                const/4 v0, 0x2
                new-array v0, v0, [I
                fill-array-data v0, :data
                goto :after
                :data
                .array-data 4
                    0x1
                    0x2
                .end array-data
                :after
                sput-object v0, LTest;->array:[I
                const/4 v1, 0x0
                return v1
            """
        )
        val gotoIndex = method.implementation!!.instructions.indexOfFirst { it.opcode == Opcode.GOTO }

        assertEquals(1, method.findFreeRegister(gotoIndex))
    }

    @Test
    fun `reuses the complete result when two paths join at a branch`() {
        // Both paths from index 1 reach the second if-eqz. The first visit must
        // remember the registers found after that branch, not only the ones before it.
        val method = method(
            registers = 2,
            body = """
                const/4 v0, 0x0
                if-eqz v0, :a
                goto :join
                :a
                goto :join
                :join
                if-eqz v0, :b
                const/4 v1, 0x1
                return-void
                :b
                const/4 v1, 0x2
                return-void
            """
        )

        assertEquals(1, method.findFreeRegister(1))
    }

    @Test
    fun `follows every path of a packed switch including no match`() {
        // v0 is read when no case matches, so only v1 is free at the switch.
        val method = method(
            registers = 3,
            parameters = "I",
            returnType = "I",
            body = """
                const/4 v0, 0x5
                packed-switch p0, :table
                return v0
                :case0
                const/4 v0, 0x1
                return v0
                :case1
                const/4 v0, 0x2
                return v0
                :table
                .packed-switch 0x0
                    :case0
                    :case1
                .end packed-switch
            """
        )

        assertEquals(1, method.findFreeRegister(1))
        assertEquals(1, method.getFreeRegisterProvider(1, 1).availableCount())
    }

    @Test
    fun `follows every case of a sparse switch`() {
        // v0 is read by the second case, so only v1 is free at the switch.
        val method = method(
            registers = 3,
            parameters = "I",
            returnType = "I",
            body = """
                const/4 v0, 0x5
                sparse-switch p0, :table
                const/4 v1, 0x0
                const/4 v0, 0x0
                add-int v0, v0, v1
                return v0
                :case_a
                const/4 v1, 0x1
                return v1
                :case_b
                const/4 v1, 0x2
                add-int/2addr v0, v1
                return v0
                :table
                .sparse-switch
                    0x10 -> :case_a
                    0x20 -> :case_b
                .end sparse-switch
            """
        )

        assertEquals(1, method.findFreeRegister(1))
        assertEquals(1, method.getFreeRegisterProvider(1, 1).availableCount())
    }
}
