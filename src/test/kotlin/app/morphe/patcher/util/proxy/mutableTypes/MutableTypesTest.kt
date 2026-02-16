/*
 * Copyright 2026 Morphe.
 * https://github.com/morpheapp/morphe-patcher
 */

package app.morphe.patcher.util.proxy.mutableTypes

import app.morphe.patcher.util.proxy.mutableTypes.MutableAnnotation.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethodParameter.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableAnnotationElement.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotation
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotationElement
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import com.android.tools.smali.dexlib2.AnnotationVisibility
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object MutableClassTest {

    @Test
    fun `MutableClass is created from ClassDef`() {
        val classDef = ImmutableClassDef(
            "Lcom/test/TestClass;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            null,
            "TestClass.java",
            null,
            null,
            null
        )

        val mutableClass = classDef.toMutable()

        assertEquals("Lcom/test/TestClass;", mutableClass.type)
        assertEquals(AccessFlags.PUBLIC.value, mutableClass.accessFlags)
        assertEquals("Ljava/lang/Object;", mutableClass.superclass)
        assertEquals("TestClass.java", mutableClass.sourceFile)
    }

    @Test
    fun `MutableClass setType changes type`() {
        val classDef = ImmutableClassDef(
            "Lcom/test/OldClass;",
            AccessFlags.PUBLIC.value,
            null, null, null, null, null, null
        )

        val mutableClass = MutableClass(classDef)
        mutableClass.setType("Lcom/test/NewClass;")

        assertEquals("Lcom/test/NewClass;", mutableClass.type)
    }

    @Test
    fun `MutableClass setAccessFlags changes access flags`() {
        val classDef = ImmutableClassDef(
            "Lcom/test/TestClass;",
            AccessFlags.PUBLIC.value,
            null, null, null, null, null, null
        )

        val mutableClass = MutableClass(classDef)
        mutableClass.setAccessFlags(AccessFlags.PRIVATE.value)

        assertEquals(AccessFlags.PRIVATE.value, mutableClass.accessFlags)
    }

    @Test
    fun `MutableClass setSourceFile changes source file`() {
        val classDef = ImmutableClassDef(
            "Lcom/test/TestClass;",
            0, null, null, "OldSource.java", null, null, null
        )

        val mutableClass = MutableClass(classDef)
        mutableClass.setSourceFile("NewSource.java")

        assertEquals("NewSource.java", mutableClass.sourceFile)
    }

    @Test
    fun `MutableClass setSuperClass changes superclass`() {
        val classDef = ImmutableClassDef(
            "Lcom/test/TestClass;",
            0, "Ljava/lang/Object;", null, null, null, null, null
        )

        val mutableClass = MutableClass(classDef)
        mutableClass.setSuperClass("Ljava/lang/Exception;")

        assertEquals("Ljava/lang/Exception;", mutableClass.superclass)
    }

    @Test
    fun `MutableClass interfaces are mutable`() {
        val classDef = ImmutableClassDef(
            "Lcom/test/TestClass;",
            0, null,
            listOf("Ljava/io/Serializable;"),
            null, null, null, null
        )

        val mutableClass = MutableClass(classDef)
        val interfaces = mutableClass.interfaces

        assertEquals(1, interfaces.size)
        assertTrue(interfaces is MutableList)

        interfaces.add("Ljava/lang/Cloneable;")
        assertEquals(2, mutableClass.interfaces.size)
    }

    @Test
    fun `MutableClass annotations are mutable`() {
        val annotation = ImmutableAnnotation(
            AnnotationVisibility.RUNTIME,
            "Ljava/lang/Deprecated;",
            emptySet()
        )
        val classDef = ImmutableClassDef(
            "Lcom/test/TestClass;",
            0, null, null, null,
            setOf(annotation),
            null, null
        )

        val mutableClass = MutableClass(classDef)
        val annotations = mutableClass.annotations

        assertEquals(1, annotations.size)
        assertTrue(annotations is MutableSet)
    }

    @Test
    fun `MutableClass methods are mutable`() {
        val method = ImmutableMethod(
            "Lcom/test/TestClass;",
            "testMethod",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value,
            null, null, null
        )
        val classDef = ImmutableClassDef(
            "Lcom/test/TestClass;",
            0, null, null, null, null, null,
            listOf(method)
        )

        val mutableClass = MutableClass(classDef)
        val methods = mutableClass.methods

        assertEquals(1, methods.size)
        assertTrue(methods is MutableSet)
    }

    @Test
    fun `MutableClass fields are mutable`() {
        val field = ImmutableField(
            "Lcom/test/TestClass;",
            "testField",
            "I",
            AccessFlags.PUBLIC.value,
            null, null, null
        )
        val classDef = ImmutableClassDef(
            "Lcom/test/TestClass;",
            0, null, null, null, null,
            listOf(field),
            null
        )

        val mutableClass = MutableClass(classDef)
        val fields = mutableClass.fields

        assertEquals(1, fields.size)
        assertTrue(fields is MutableSet)
    }

    @Test
    fun `MutableClass separates static and instance fields`() {
        val staticField = ImmutableField(
            "Lcom/test/TestClass;",
            "staticField",
            "I",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null, null, null
        )
        val instanceField = ImmutableField(
            "Lcom/test/TestClass;",
            "instanceField",
            "I",
            AccessFlags.PUBLIC.value,
            null, null, null
        )
        val classDef = ImmutableClassDef(
            "Lcom/test/TestClass;",
            0, null, null, null, null,
            listOf(staticField, instanceField),
            null
        )

        val mutableClass = MutableClass(classDef)

        assertEquals(2, mutableClass.fields.size)
        assertEquals(1, mutableClass.staticFields.size)
        assertEquals(1, mutableClass.instanceFields.size)
    }

    @Test
    fun `MutableClass separates direct and virtual methods`() {
        val directMethod = ImmutableMethod(
            "Lcom/test/TestClass;",
            "<init>",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            null, null, null
        )
        val virtualMethod = ImmutableMethod(
            "Lcom/test/TestClass;",
            "virtualMethod",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value,
            null, null, null
        )
        val classDef = ImmutableClassDef(
            "Lcom/test/TestClass;",
            0, null, null, null, null, null,
            listOf(directMethod, virtualMethod)
        )

        val mutableClass = MutableClass(classDef)

        assertEquals(2, mutableClass.methods.size)
        assertEquals(1, mutableClass.directMethods.size)
        assertEquals(1, mutableClass.virtualMethods.size)
    }
}

internal object MutableMethodTest {

    @Test
    fun `MutableMethod is created from Method`() {
        val method = ImmutableMethod(
            "Lcom/test/TestClass;",
            "testMethod",
            listOf(ImmutableMethodParameter("I", null, "param1")),
            "Ljava/lang/String;",
            AccessFlags.PUBLIC.value,
            null, null, null
        )

        val mutableMethod = method.toMutable()

        assertEquals("Lcom/test/TestClass;", mutableMethod.definingClass)
        assertEquals("testMethod", mutableMethod.name)
        assertEquals("Ljava/lang/String;", mutableMethod.returnType)
        assertEquals(AccessFlags.PUBLIC.value, mutableMethod.accessFlags)
    }

    @Test
    fun `MutableMethod setDefiningClass changes defining class`() {
        val method = ImmutableMethod(
            "Lcom/test/OldClass;",
            "testMethod",
            emptyList(), "V", 0, null, null, null
        )

        val mutableMethod = MutableMethod(method)
        mutableMethod.setDefiningClass("Lcom/test/NewClass;")

        assertEquals("Lcom/test/NewClass;", mutableMethod.definingClass)
    }

    @Test
    fun `MutableMethod setName changes name`() {
        val method = ImmutableMethod(
            "Lcom/test/TestClass;",
            "oldName",
            emptyList(), "V", 0, null, null, null
        )

        val mutableMethod = MutableMethod(method)
        mutableMethod.setName("newName")

        assertEquals("newName", mutableMethod.name)
    }

    @Test
    fun `MutableMethod setAccessFlags changes access flags`() {
        val method = ImmutableMethod(
            "Lcom/test/TestClass;",
            "testMethod",
            emptyList(), "V", AccessFlags.PUBLIC.value, null, null, null
        )

        val mutableMethod = MutableMethod(method)
        mutableMethod.setAccessFlags(AccessFlags.PRIVATE.value)

        assertEquals(AccessFlags.PRIVATE.value, mutableMethod.accessFlags)
    }

    @Test
    fun `MutableMethod setReturnType changes return type`() {
        val method = ImmutableMethod(
            "Lcom/test/TestClass;",
            "testMethod",
            emptyList(), "V", 0, null, null, null
        )

        val mutableMethod = MutableMethod(method)
        mutableMethod.setReturnType("I")

        assertEquals("I", mutableMethod.returnType)
    }

    @Test
    fun `MutableMethod parameters are mutable`() {
        val method = ImmutableMethod(
            "Lcom/test/TestClass;",
            "testMethod",
            listOf(ImmutableMethodParameter("I", null, "param1")),
            "V", 0, null, null, null
        )

        val mutableMethod = MutableMethod(method)
        val parameters = mutableMethod.parameters

        assertEquals(1, parameters.size)
        assertTrue(parameters is MutableList)
    }

    @Test
    fun `MutableMethod parameterTypes are mutable`() {
        val method = ImmutableMethod(
            "Lcom/test/TestClass;",
            "testMethod",
            listOf(ImmutableMethodParameter("I", null, "param1")),
            "V", 0, null, null, null
        )

        val mutableMethod = MutableMethod(method)
        val parameterTypes = mutableMethod.parameterTypes

        assertEquals(1, parameterTypes.size)
        assertTrue(parameterTypes is MutableList)
    }

    @Test
    fun `MutableMethod implementation is accessible`() {
        val implementation = MutableMethodImplementation(4)
        val method = ImmutableMethod(
            "Lcom/test/TestClass;",
            "testMethod",
            emptyList(), "V", 0, null, null,
            implementation
        )

        val mutableMethod = MutableMethod(method)

        assertNotNull(mutableMethod.implementation)
    }

    @Test
    fun `MutableMethod without implementation returns null`() {
        val method = ImmutableMethod(
            "Lcom/test/TestClass;",
            "testMethod",
            emptyList(), "V", AccessFlags.ABSTRACT.value, null, null, null
        )

        val mutableMethod = MutableMethod(method)

        assertNull(mutableMethod.implementation)
    }

    @Test
    fun `MutableMethod annotations are mutable`() {
        val annotation = ImmutableAnnotation(
            AnnotationVisibility.RUNTIME,
            "Ljava/lang/Override;",
            emptySet()
        )
        val method = ImmutableMethod(
            "Lcom/test/TestClass;",
            "testMethod",
            emptyList(), "V", 0,
            setOf(annotation),
            null, null
        )

        val mutableMethod = MutableMethod(method)
        val annotations = mutableMethod.annotations

        assertEquals(1, annotations.size)
        assertTrue(annotations is MutableSet)
    }
}

internal object MutableFieldTest {

    @Test
    fun `MutableField is created from Field`() {
        val field = ImmutableField(
            "Lcom/test/TestClass;",
            "testField",
            "I",
            AccessFlags.PUBLIC.value,
            null, null, null
        )

        val mutableField = field.toMutable()

        assertEquals("Lcom/test/TestClass;", mutableField.definingClass)
        assertEquals("testField", mutableField.name)
        assertEquals("I", mutableField.type)
        assertEquals(AccessFlags.PUBLIC.value, mutableField.accessFlags)
    }

    @Test
    fun `MutableField setDefiningClass changes defining class`() {
        val field = ImmutableField(
            "Lcom/test/OldClass;",
            "testField",
            "I", 0, null, null, null
        )

        val mutableField = MutableField(field)
        mutableField.setDefiningClass("Lcom/test/NewClass;")

        assertEquals("Lcom/test/NewClass;", mutableField.definingClass)
    }

    @Test
    fun `MutableField setName changes name`() {
        val field = ImmutableField(
            "Lcom/test/TestClass;",
            "oldName",
            "I", 0, null, null, null
        )

        val mutableField = MutableField(field)
        mutableField.setName("newName")

        assertEquals("newName", mutableField.name)
    }

    @Test
    fun `MutableField setType changes type`() {
        val field = ImmutableField(
            "Lcom/test/TestClass;",
            "testField",
            "I", 0, null, null, null
        )

        val mutableField = MutableField(field)
        mutableField.setType("J")

        assertEquals("J", mutableField.type)
    }

    @Test
    fun `MutableField setAccessFlags changes access flags`() {
        val field = ImmutableField(
            "Lcom/test/TestClass;",
            "testField",
            "I", AccessFlags.PUBLIC.value, null, null, null
        )

        val mutableField = MutableField(field)
        mutableField.setAccessFlags(AccessFlags.PRIVATE.value)

        assertEquals(AccessFlags.PRIVATE.value, mutableField.accessFlags)
    }

    @Test
    fun `MutableField annotations are mutable`() {
        val annotation = ImmutableAnnotation(
            AnnotationVisibility.RUNTIME,
            "Ljava/lang/Deprecated;",
            emptySet()
        )
        val field = ImmutableField(
            "Lcom/test/TestClass;",
            "testField",
            "I", 0,
            null,
            setOf(annotation),
            null
        )

        val mutableField = MutableField(field)
        val annotations = mutableField.annotations

        assertEquals(1, annotations.size)
        assertTrue(annotations is MutableSet)
    }
}

internal object MutableMethodParameterTest {

    @Test
    fun `MutableMethodParameter is created from MethodParameter`() {
        val parameter = ImmutableMethodParameter(
            "Ljava/lang/String;",
            null,
            "paramName"
        )

        val mutableParameter = parameter.toMutable()

        assertEquals("Ljava/lang/String;", mutableParameter.type)
        assertEquals("paramName", mutableParameter.name)
    }

    @Test
    fun `MutableMethodParameter preserves type`() {
        val parameter = ImmutableMethodParameter("I", null, null)

        val mutableParameter = MutableMethodParameter(parameter)

        assertEquals("I", mutableParameter.type)
    }

    @Test
    fun `MutableMethodParameter preserves name`() {
        val parameter = ImmutableMethodParameter("I", null, "myParam")

        val mutableParameter = MutableMethodParameter(parameter)

        assertEquals("myParam", mutableParameter.name)
    }

    @Test
    fun `MutableMethodParameter handles null name`() {
        val parameter = ImmutableMethodParameter("I", null, null)

        val mutableParameter = MutableMethodParameter(parameter)

        assertNull(mutableParameter.name)
    }

    @Test
    fun `MutableMethodParameter preserves signature`() {
        val parameter = ImmutableMethodParameter("Ljava/util/List;", setOf(), "listParam")

        val mutableParameter = MutableMethodParameter(parameter)

        // Signature is typically null in most cases
        assertNull(mutableParameter.signature)
    }

    @Test
    fun `MutableMethodParameter annotations are mutable`() {
        val annotation = ImmutableAnnotation(
            AnnotationVisibility.RUNTIME,
            "Landroid/annotation/NonNull;",
            emptySet()
        )
        val parameter = ImmutableMethodParameter(
            "Ljava/lang/String;",
            setOf(annotation),
            null
        )

        val mutableParameter = MutableMethodParameter(parameter)
        val annotations = mutableParameter.annotations

        assertEquals(1, annotations.size)
        assertTrue(annotations is MutableSet)
    }
}

internal object MutableAnnotationTest {

    @Test
    fun `MutableAnnotation is created from Annotation`() {
        val element = ImmutableAnnotationElement("value", ImmutableStringEncodedValue("test"))
        val annotation = ImmutableAnnotation(
            AnnotationVisibility.RUNTIME,
            "Ljava/lang/Deprecated;",
            setOf(element)
        )

        val mutableAnnotation = annotation.toMutable()

        assertEquals(AnnotationVisibility.RUNTIME, mutableAnnotation.visibility)
        assertEquals("Ljava/lang/Deprecated;", mutableAnnotation.type)
        assertEquals(1, mutableAnnotation.elements.size)
    }

    @Test
    fun `MutableAnnotation elements are mutable`() {
        val element = ImmutableAnnotationElement("value", ImmutableStringEncodedValue("test"))
        val annotation = ImmutableAnnotation(
            AnnotationVisibility.RUNTIME,
            "Lcom/test/Annotation;",
            setOf(element)
        )

        val mutableAnnotation = MutableAnnotation(annotation)
        val elements = mutableAnnotation.elements

        assertEquals(1, elements.size)
        assertTrue(elements is MutableSet)
    }

    @Test
    fun `MutableAnnotation preserves visibility`() {
        val annotation = ImmutableAnnotation(
            AnnotationVisibility.BUILD,
            "Lcom/test/Annotation;",
            emptySet()
        )

        val mutableAnnotation = MutableAnnotation(annotation)

        assertEquals(AnnotationVisibility.BUILD, mutableAnnotation.visibility)
    }

    @Test
    fun `MutableAnnotation preserves type`() {
        val annotation = ImmutableAnnotation(
            AnnotationVisibility.RUNTIME,
            "Lcom/test/CustomAnnotation;",
            emptySet()
        )

        val mutableAnnotation = MutableAnnotation(annotation)

        assertEquals("Lcom/test/CustomAnnotation;", mutableAnnotation.type)
    }
}

internal object MutableAnnotationElementTest {

    @Test
    fun `MutableAnnotationElement is created from AnnotationElement`() {
        val element = ImmutableAnnotationElement("testName", ImmutableStringEncodedValue("testValue"))

        val mutableElement = element.toMutable()

        assertEquals("testName", mutableElement.name)
        assertNotNull(mutableElement.value)
    }

    @Test
    fun `MutableAnnotationElement setName changes name`() {
        val element = ImmutableAnnotationElement("oldName", ImmutableStringEncodedValue("value"))

        val mutableElement = MutableAnnotationElement(element)
        mutableElement.setName("newName")

        assertEquals("newName", mutableElement.name)
    }
}

