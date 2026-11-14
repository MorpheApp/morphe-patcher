/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

@file:Suppress("unused")

package app.morphe.patcher.util

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import org.antlr.runtime.Token
import java.io.StringReader

// Extension code cannot call obfuscated app code directly, because the names differ between app
// versions. Instead the extension declares an interface, and a patch adds that interface to the
// app class along with the methods that implement it. The extension then casts the app object
// to the interface. The functions of this file add the interface and one of its methods.

private const val OBJECT_TYPE = "Ljava/lang/Object;"

/**
 * Finds a class of the app or of the extensions, or null if there is no such class.
 */
internal typealias ClassResolver = (type: String) -> ClassDef?

/**
 * Adds an interface to this class if it does not have it already, and adds a method that implements
 * a method of the interface using smali code.
 *
 * The parameters, the return type and the number of registers come from the interface method and
 * from the smali code. The smali code can use any `v` registers and they are not shared with the
 * `p` registers. `p0` is this class, and the method parameters start at `p1`.
 *
 * The added method is public and final. It is only public if this class is itself an interface.
 *
 * @param interfaceName The interface, either as a type such as `Lcom/foo/Outer$Inner;`
 *                      or as a class name such as `com.foo.Outer$Inner`.
 * @param methodName The name of the interface method to implement. Can be a method of an
 *                   interface that the interface extends.
 * @param smali The instructions of the method. Can use labels.
 * @param parameters The parameter types of the interface method. Only needed if the interface
 *                   has more than one method with the same name.
 * @return The method added to this class.
 * @throws PatchException If the interface or the interface method does not exist, if more than one
 *                        interface method has the name and no [parameters] are given,
 *                        or if this class already has the method.
 */
fun MutableClass.addPatchInterfaceMethod(
    interfaceName: String,
    methodName: String,
    smali: String,
    parameters: List<String>? = null,
): MutableMethod = addPatchInterfaceMethod(::classDefByOrNull, interfaceName, methodName, smali, parameters)

/**
 * Adds an interface to this class if it does not have it already, and adds a method that implements
 * a method of the interface by calling an existing method. This gives an obfuscated method a name
 * that extension code can call.
 *
 * The parameters of the interface method are passed on in the same order. The primitive types must
 * be the same. Object types can differ, and are then cast to the type the called method declares.
 * This way the interface can declare `Object` where the app uses an obfuscated class.
 * The same applies to the return type. An interface method that returns void can call a method
 * that returns a value.
 *
 * @param interfaceName The interface, either as a type such as `Lcom/foo/Outer$Inner;`
 *                      or as a class name such as `com.foo.Outer$Inner`.
 * @param methodName The name of the interface method to implement. Can be a method of an
 *                   interface that the interface extends.
 * @param reference The method to call. Either a method of this class or of a class it extends,
 *                  or any static method. Cannot be a constructor.
 * @param parameters The parameter types of the interface method. Only needed if the interface
 *                   has more than one method with the same name.
 * @return The method added to this class.
 * @throws PatchException If the interface or the interface method does not exist, if this class
 *                        already has the method, or if [reference] cannot be called from
 *                        the interface method.
 */
fun MutableClass.addPatchInterfaceMethod(
    interfaceName: String,
    methodName: String,
    reference: MethodReference,
    parameters: List<String>? = null,
): MutableMethod = addPatchInterfaceMethod(::classDefByOrNull, interfaceName, methodName, reference, parameters)

/**
 * Adds an interface to this class if it does not have it already, and adds a method that implements
 * a method of the interface by reading or writing a field. This gives an obfuscated field a name
 * that extension code can use.
 *
 * An interface method with no parameters that returns a value gets the field.
 * An interface method with one parameter that returns void sets the field.
 *
 * The primitive types must be the same. Object types can differ and are then cast.
 * This way the interface can declare `Object` where the app uses an obfuscated class.
 *
 * @param interfaceName The interface, either as a type such as `Lcom/foo/Outer$Inner;`
 *                      or as a class name such as `com.foo.Outer$Inner`.
 * @param methodName The name of the interface method to implement. Can be a method of an
 *                   interface that the interface extends.
 * @param field The field to get or set. Either a field of this class or of a class it extends,
 *              or any static field.
 * @param parameters The parameter types of the interface method. Only needed if the interface
 *                   has more than one method with the same name.
 * @return The method added to this class.
 * @throws PatchException If the interface or the interface method does not exist, if this class
 *                        already has the method, or if the interface method cannot get or
 *                        set [field].
 */
fun MutableClass.addPatchInterfaceMethod(
    interfaceName: String,
    methodName: String,
    field: FieldReference,
    parameters: List<String>? = null,
): MutableMethod = addPatchInterfaceMethod(::classDefByOrNull, interfaceName, methodName, field, parameters)

internal fun MutableClass.addPatchInterfaceMethod(
    resolver: ClassResolver,
    interfaceName: String,
    methodName: String,
    smali: String,
    parameters: List<String>?,
): MutableMethod {
    val interfaceType = interfaceName.toClassType()
    val interfaceMethod = findInterfaceMethod(resolver, interfaceType, methodName, parameters)

    return addImplementation(
        interfaceType,
        interfaceMethod,
        localRegisters = localRegisterCount(smali),
        smali,
    )
}

internal fun MutableClass.addPatchInterfaceMethod(
    resolver: ClassResolver,
    interfaceName: String,
    methodName: String,
    reference: MethodReference,
    parameters: List<String>?,
): MutableMethod {
    val interfaceType = interfaceName.toClassType()
    val interfaceMethod = findInterfaceMethod(resolver, interfaceType, methodName, parameters)

    if (reference.name == "<init>" || reference.name == "<clinit>") {
        throw PatchException("Cannot call a constructor from an interface method: $reference")
    }

    val targetClass = resolver(reference.definingClass)
    // Null if the method is inherited or is not part of the app, and then it is a virtual method.
    val targetMethod = reference as? Method ?: targetClass?.methods?.find { method ->
        method.name == reference.name &&
            method.returnType == reference.returnType &&
            method.parameterTypes.sameTypes(reference.parameterTypes)
    }
    val targetAccessFlags = targetMethod?.accessFlags ?: 0
    val isStatic = AccessFlags.STATIC.isSet(targetAccessFlags)

    if (!isStatic) {
        requireMemberOfThisClass(resolver, reference.definingClass, reference)
    }

    val interfaceParameters = interfaceMethod.parameterTypes.map(CharSequence::toString)
    val targetParameters = reference.parameterTypes.map(CharSequence::toString)
    if (interfaceParameters.size != targetParameters.size) {
        throw PatchException(
            "Interface method ${interfaceMethod.describe()} has ${interfaceParameters.size} " +
                "parameters but the method to call has ${targetParameters.size}: $reference"
        )
    }

    val instructions = StringBuilder()

    // Parameter registers start after p0, which is this class.
    var register = 1
    val firstArgumentRegister = if (isStatic) 1 else 0
    interfaceParameters.forEachIndexed { index, interfaceType ->
        val targetType = targetParameters[index]
        if (needsCast(from = interfaceType, to = targetType, interfaceMethod, reference)) {
            instructions.appendLine("check-cast p$register, $targetType")
        }
        register += interfaceType.registerWidth
    }
    val lastArgumentRegister = register - 1
    val argumentRegisters = lastArgumentRegister - firstArgumentRegister + 1

    val invoke = when {
        isStatic -> "invoke-static"
        AccessFlags.PRIVATE.isSet(targetAccessFlags) -> "invoke-direct"
        targetClass != null && AccessFlags.INTERFACE.isSet(targetClass.accessFlags) -> "invoke-interface"
        else -> "invoke-virtual"
    }
    // The argument registers are always consecutive, so a range can pass more than 5 of them.
    instructions.appendLine(
        when {
            argumentRegisters <= 0 -> "$invoke { }, $reference"
            argumentRegisters <= 5 -> {
                val registers = (firstArgumentRegister..lastArgumentRegister).joinToString { "p$it" }
                "$invoke { $registers }, $reference"
            }

            else -> "$invoke/range { p$firstArgumentRegister .. p$lastArgumentRegister }, $reference"
        }
    )

    val returnType = interfaceMethod.returnType
    var localRegisters = 0
    if (returnType == "V") {
        instructions.appendLine("return-void")
    } else {
        localRegisters = returnType.registerWidth
        val suffix = returnType.instructionSuffix
        instructions.appendLine("move-result$suffix v0")
        if (needsCast(from = reference.returnType, to = returnType, interfaceMethod, reference)) {
            instructions.appendLine("check-cast v0, $returnType")
        }
        instructions.appendLine("return$suffix v0")
    }

    return addImplementation(interfaceType, interfaceMethod, localRegisters, instructions.toString())
}

internal fun MutableClass.addPatchInterfaceMethod(
    resolver: ClassResolver,
    interfaceName: String,
    methodName: String,
    field: FieldReference,
    parameters: List<String>?,
): MutableMethod {
    val interfaceType = interfaceName.toClassType()
    val interfaceMethod = findInterfaceMethod(resolver, interfaceType, methodName, parameters)

    val targetField = field as? Field
        ?: resolver(field.definingClass)?.fields?.find { it.name == field.name && it.type == field.type }
        ?: throw PatchException("Could not find field: $field")
    val isStatic = AccessFlags.STATIC.isSet(targetField.accessFlags)

    if (!isStatic) {
        requireMemberOfThisClass(resolver, field.definingClass, field)
    }

    val interfaceParameters = interfaceMethod.parameterTypes.map(CharSequence::toString)
    val returnType = interfaceMethod.returnType
    val fieldSuffix = field.type.fieldInstructionSuffix
    val instructions = StringBuilder()
    var localRegisters = 0

    when {
        interfaceParameters.isEmpty() && returnType != "V" -> {
            localRegisters = returnType.registerWidth
            instructions.appendLine(
                if (isStatic) "sget$fieldSuffix v0, $field" else "iget$fieldSuffix v0, p0, $field"
            )
            if (needsCast(from = field.type, to = returnType, interfaceMethod, field)) {
                instructions.appendLine("check-cast v0, $returnType")
            }
            instructions.appendLine("return${returnType.instructionSuffix} v0")
        }

        interfaceParameters.size == 1 && returnType == "V" -> {
            if (needsCast(from = interfaceParameters[0], to = field.type, interfaceMethod, field)) {
                instructions.appendLine("check-cast p1, ${field.type}")
            }
            instructions.appendLine(
                if (isStatic) "sput$fieldSuffix p1, $field" else "iput$fieldSuffix p1, p0, $field"
            )
            instructions.appendLine("return-void")
        }

        else -> throw PatchException(
            "Interface method ${interfaceMethod.describe()} is neither a getter with no parameters " +
                "and a return value, nor a setter with one parameter and no return value"
        )
    }

    return addImplementation(interfaceType, interfaceMethod, localRegisters, instructions.toString())
}

/**
 * Adds [interfaceType] to this class, and a method implementing [interfaceMethod].
 *
 * @param localRegisters The number of `v` registers the instructions use.
 */
private fun MutableClass.addImplementation(
    interfaceType: String,
    interfaceMethod: Method,
    localRegisters: Int,
    smali: String,
): MutableMethod {
    val parameterTypes = interfaceMethod.parameterTypes.map(CharSequence::toString)

    val existingMethod = methods.find { method ->
        method.name == interfaceMethod.name && method.parameterTypes.sameTypes(parameterTypes)
    }
    if (existingMethod != null) {
        throw PatchException("Class $type already has method: ${existingMethod.name}" +
                "(${parameterTypes.joinToString("")})${existingMethod.returnType}")
    }

    // The receiver, and the parameters. Long and double use two registers.
    val parameterRegisters = 1 + parameterTypes.sumOf { it.registerWidth }

    // A default method of an interface cannot be final.
    val methodAccessFlags = if (AccessFlags.INTERFACE.isSet(accessFlags)) {
        AccessFlags.PUBLIC.value
    } else {
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
    }

    val method = ImmutableMethod(
        type,
        interfaceMethod.name,
        interfaceMethod.parameters.map { ImmutableMethodParameter(it.type, null, it.name) },
        interfaceMethod.returnType,
        methodAccessFlags,
        null,
        null,
        MutableMethodImplementation(localRegisters + parameterRegisters),
    ).toMutable()

    try {
        method.addInstructionsWithLabels(0, smali)
    } catch (ex: Exception) {
        throw PatchException(
            "Could not compile the instructions of ${interfaceMethod.describe()} " +
                "added to $type:\n${smali.trimIndent()}",
            ex,
        )
    }

    if (interfaceType !in interfaces) {
        interfaces.add(interfaceType)
    }
    methods.add(method)

    return method
}

private fun findInterfaceMethod(
    resolver: ClassResolver,
    interfaceType: String,
    methodName: String,
    parameters: List<String>?,
): Method {
    val interfaceClass = resolver(interfaceType)
        ?: throw PatchException("Could not find interface: $interfaceType")
    if (!AccessFlags.INTERFACE.isSet(interfaceClass.accessFlags)) {
        throw PatchException("Class is not an interface: $interfaceType")
    }

    // The methods of the interface, and the methods of the interfaces it extends.
    // A method closer to the interface replaces the same method of an interface further away.
    val named = ArrayList<Method>()
    val seen = HashSet<String>()
    val pending = ArrayDeque<ClassDef>()
    pending += interfaceClass
    while (pending.isNotEmpty()) {
        val classDef = pending.removeFirst()
        classDef.methods.filterTo(named) { method ->
            method.name == methodName &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                named.none { it.parameterTypes.sameTypes(method.parameterTypes) }
        }
        classDef.interfaces.forEach { parent ->
            if (seen.add(parent)) resolver(parent)?.let(pending::add)
        }
    }
    val matches = if (parameters == null) {
        named
    } else {
        named.filter { it.parameterTypes.sameTypes(parameters) }
    }

    return when (matches.size) {
        1 -> matches.single()

        0 -> throw PatchException(
            "Could not find method $methodName" +
                (parameters?.joinToString("", "(", ")") ?: "") +
                " in interface $interfaceType" +
                if (named.isEmpty()) "" else ". Methods with this name: " +
                    named.joinToString { it.describe() }
        )

        else -> throw PatchException(
            "Interface $interfaceType has more than one method named $methodName, so the parameters " +
                "must be given. Methods: " + matches.joinToString { it.describe() }
        )
    }
}

/**
 * Checks that a non static member can be used with this class as the receiver.
 */
private fun MutableClass.requireMemberOfThisClass(
    resolver: ClassResolver,
    definingClass: String,
    member: Any,
) {
    if (definingClass == type) return

    // A class that is not part of the app is a framework class. All classes extend one of these,
    // and which ones is not known, so it cannot be checked. A class of the app can be checked,
    // since a framework class never extends an app class.
    if (resolver(definingClass) == null) return

    val seen = HashSet<String>()
    val pending = ArrayDeque<ClassDef>()
    pending += this
    while (pending.isNotEmpty()) {
        val classDef = pending.removeFirst()
        val parents = classDef.interfaces + listOfNotNull(classDef.superclass)
        for (parent in parents) {
            if (parent == definingClass) return
            if (seen.add(parent)) resolver(parent)?.let(pending::add)
        }
    }

    throw PatchException("Class $type does not extend the class of: $member")
}

/**
 * @return If a value of type [from] needs a cast to be used as type [to].
 * @throws PatchException If the types differ and a cast cannot convert between them.
 */
private fun needsCast(from: String, to: String, interfaceMethod: Method, target: Any): Boolean {
    if (from == to) return false

    if (from.isPrimitiveType || to.isPrimitiveType) {
        throw PatchException(
            "Type $from cannot be used as type $to. " +
                "Interface method: ${interfaceMethod.describe()} Target: $target"
        )
    }

    // Everything is an object, so no cast is needed.
    return to != OBJECT_TYPE
}

/**
 * The number of `v` registers that smali instructions use.
 */
internal fun localRegisterCount(smali: String): Int {
    // A regular expression is not used because obfuscated classes, fields and methods
    // can have the name of a register, such as: La;->v20()V
    val lexer = smaliFlexLexer(StringReader(smali), 15)
    var highestRegister = -1
    var previousType = Token.EOF
    while (true) {
        val token = lexer.nextToken()
        val tokenType = token.type
        if (tokenType == Token.EOF) break
        if (token.channel == Token.HIDDEN_CHANNEL) continue

        // The lexer also reads the name of a member or of a label as a register,
        // if it looks like one. These follow an arrow or a colon.
        val isName = previousType == smaliParser.ARROW || previousType == smaliParser.COLON
        previousType = tokenType
        if (tokenType != smaliParser.REGISTER || isName) continue

        val text = token.text
        if (text[0] == 'v') {
            highestRegister = maxOf(highestRegister, text.substring(1).toInt())
        }
    }

    // One more than the highest register, in case the highest register
    // holds a long or a double that also uses the register after it.
    return if (highestRegister < 0) 0 else highestRegister + 2
}

private fun String.toClassType() = if (startsWith('L') && endsWith(';')) {
    this
} else {
    "L" + replace('.', '/') + ";"
}

private fun Method.describe() =
    "$name(${parameterTypes.joinToString("")})$returnType"

private fun List<CharSequence>.sameTypes(other: List<CharSequence>) =
    size == other.size && indices.all { this[it].toString() == other[it].toString() }

private val String.isPrimitiveType
    get() = !startsWith('L') && !startsWith('[')

private val String.registerWidth
    get() = if (this == "J" || this == "D") 2 else 1

/**
 * The suffix of move-result and return instructions for a type.
 */
private val String.instructionSuffix
    get() = when {
        this == "J" || this == "D" -> "-wide"
        isPrimitiveType -> ""
        else -> "-object"
    }

/**
 * The suffix of iget, iput, sget and sput instructions for a type.
 */
private val String.fieldInstructionSuffix
    get() = when (this) {
        "Z" -> "-boolean"
        "B" -> "-byte"
        "C" -> "-char"
        "S" -> "-short"
        "I", "F" -> ""
        "J", "D" -> "-wide"
        else -> "-object"
    }
