/*
 * Copyright 2026 Morphe.
 * https://github.com/morpheapp/morphe-patcher
 */

package app.morphe.patcher.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal object DocumentTest {

    @TempDir
    lateinit var tempDir: File

    private val simpleXml = """<?xml version="1.0" encoding="UTF-8"?>
        <root>
            <element attr="value">text content</element>
        </root>
    """.trimIndent()

    private val complexXml = """<?xml version="1.0" encoding="UTF-8"?>
        <manifest package="com.test.app">
            <application android:name=".MyApp">
                <activity android:name=".MainActivity"/>
            </application>
        </manifest>
    """.trimIndent()

    // ==================== Constructor with InputStream tests ====================

    @Test
    fun `Document created from InputStream parses XML correctly`() {
        val inputStream = ByteArrayInputStream(simpleXml.toByteArray())

        val document = Document(inputStream)

        assertNotNull(document)
        assertNotNull(document.documentElement)
        assertEquals("root", document.documentElement.tagName)
    }

    @Test
    fun `Document parses elements and attributes`() {
        val inputStream = ByteArrayInputStream(simpleXml.toByteArray())

        val document = Document(inputStream)
        val elements = document.getElementsByTagName("element")

        assertEquals(1, elements.length)
        assertEquals("value", elements.item(0).attributes.getNamedItem("attr").nodeValue)
        assertEquals("text content", elements.item(0).textContent)
    }

    @Test
    fun `Document parses complex XML structure`() {
        val inputStream = ByteArrayInputStream(complexXml.toByteArray())

        val document = Document(inputStream)

        assertEquals("manifest", document.documentElement.tagName)
        assertEquals("com.test.app", document.documentElement.getAttribute("package"))
    }

    // ==================== Document modification tests ====================

    @Test
    fun `Document allows element creation`() {
        val inputStream = ByteArrayInputStream(simpleXml.toByteArray())

        val document = Document(inputStream)
        val newElement = document.createElement("newElement")
        newElement.textContent = "new content"
        document.documentElement.appendChild(newElement)

        val newElements = document.getElementsByTagName("newElement")
        assertEquals(1, newElements.length)
        assertEquals("new content", newElements.item(0).textContent)
    }

    @Test
    fun `Document allows attribute modification`() {
        val inputStream = ByteArrayInputStream(simpleXml.toByteArray())

        val document = Document(inputStream)
        val element = document.getElementsByTagName("element").item(0) as org.w3c.dom.Element
        element.setAttribute("newAttr", "newValue")

        assertEquals("newValue", element.getAttribute("newAttr"))
    }

    @Test
    fun `Document allows element removal`() {
        val inputStream = ByteArrayInputStream(simpleXml.toByteArray())

        val document = Document(inputStream)
        val element = document.getElementsByTagName("element").item(0)
        element.parentNode.removeChild(element)

        assertEquals(0, document.getElementsByTagName("element").length)
    }

    // ==================== Constructor with File and close tests ====================

    @Test
    fun `Document created from File can be modified and saved`() {
        val tempFile = File(tempDir, "test.xml")
        tempFile.writeText(simpleXml)

        val document = Document(tempFile)
        val element = document.getElementsByTagName("element").item(0) as org.w3c.dom.Element
        element.setAttribute("modified", "true")
        document.close()

        // Read the file again to verify changes were saved
        val savedContent = tempFile.readText()
        assertTrue(savedContent.contains("modified=\"true\""))
    }

    @Test
    fun `Document throws when multiple instances read same file`() {
        val tempFile = File(tempDir, "concurrent.xml")
        tempFile.writeText(simpleXml)

        val document1 = Document(tempFile)
        @Suppress("UNUSED_VARIABLE")
        val document2 = Document(tempFile)

        // First close should fail because another instance is reading
        assertThrows<IllegalStateException> {
            document1.close()
        }
    }

    @Test
    fun `Document from InputStream close does not write`() {
        val inputStream = ByteArrayInputStream(simpleXml.toByteArray())

        val document = Document(inputStream)
        // Modifying document from input stream
        val element = document.getElementsByTagName("element").item(0) as org.w3c.dom.Element
        element.setAttribute("modified", "true")

        // Close should not throw - no file to write to
        document.close()
    }

    // ==================== Edge case tests ====================

    @Test
    fun `Document handles empty root element`() {
        val emptyXml = """<?xml version="1.0" encoding="UTF-8"?><root/>"""
        val inputStream = ByteArrayInputStream(emptyXml.toByteArray())

        val document = Document(inputStream)

        assertEquals("root", document.documentElement.tagName)
        assertEquals(0, document.documentElement.childNodes.length)
    }

    @Test
    fun `Document handles nested elements`() {
        val nestedXml = """<?xml version="1.0" encoding="UTF-8"?>
            <a><b><c><d>deep</d></c></b></a>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(nestedXml.toByteArray())

        val document = Document(inputStream)
        val deepElement = document.getElementsByTagName("d").item(0)

        assertEquals("deep", deepElement.textContent)
    }

    @Test
    fun `Document handles special characters`() {
        val specialXml = """<?xml version="1.0" encoding="UTF-8"?>
            <root><element>Special &amp; chars &lt;&gt;</element></root>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(specialXml.toByteArray())

        val document = Document(inputStream)
        val element = document.getElementsByTagName("element").item(0)

        assertEquals("Special & chars <>", element.textContent)
    }

    @Test
    fun `Document handles CDATA sections`() {
        val cdataXml = """<?xml version="1.0" encoding="UTF-8"?>
            <root><element><![CDATA[<raw>content</raw>]]></element></root>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(cdataXml.toByteArray())

        val document = Document(inputStream)
        val element = document.getElementsByTagName("element").item(0)

        assertTrue(element.textContent.contains("<raw>content</raw>"))
    }

    @Test
    fun `Document throws on invalid XML`() {
        val invalidXml = """<root><unclosed>"""
        val inputStream = ByteArrayInputStream(invalidXml.toByteArray())

        assertThrows<org.xml.sax.SAXException> {
            Document(inputStream)
        }
    }

    @Test
    fun `Document handles namespaces`() {
        val namespacedXml = """<?xml version="1.0" encoding="UTF-8"?>
            <root xmlns:ns="http://example.com/ns">
                <ns:element>namespaced</ns:element>
            </root>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(namespacedXml.toByteArray())

        val document = Document(inputStream)
        // getElementsByTagName returns elements regardless of namespace prefix
        val elements = document.getElementsByTagName("ns:element")

        assertEquals(1, elements.length)
    }

    @Test
    fun `Document handles comments`() {
        val commentXml = """<?xml version="1.0" encoding="UTF-8"?>
            <root><!-- This is a comment --><element/></root>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(commentXml.toByteArray())

        val document = Document(inputStream)

        assertNotNull(document.documentElement)
        assertEquals(1, document.getElementsByTagName("element").length)
    }
}
