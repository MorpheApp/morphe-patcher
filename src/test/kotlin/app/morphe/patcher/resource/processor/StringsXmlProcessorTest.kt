/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.parseXml
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.xmlpull.v1.XmlPullParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Checks that processing strings without parsing the document gives the strings an XML parser gives.
 */
internal class StringsXmlProcessorTest {
    @TempDir
    lateinit var tempDir: File

    private val escaper = StringsXmlEscapeProcessor({ _, _ -> File("") }, emptyMap())
    private val unescaper = StringsXmlUnEscapeProcessor({ _, _ -> File("") }, emptyMap())

    @ParameterizedTest
    @MethodSource("documents")
    fun `escaping gives the strings of an XML parser`(xml: String) = assertSameStrings(escaper, xml)

    @ParameterizedTest
    @MethodSource("documents")
    fun `unescaping gives the strings of an XML parser`(xml: String) = assertSameStrings(unescaper, xml)

    @Test
    fun `a document without changes is returned as it is`() {
        val xml = """<resources><string name="a">Plain &amp; simple</string><string name="b"/></resources>"""
        assertSame(xml, escaper.processXml(xml))
        assertSame(xml, unescaper.processXml(xml))
    }

    @Test
    fun `only the changed text is rewritten`() {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" +
            "  <!-- <string name=\"commented\">it's</string> -->\n" +
            "  <string name=\"a\" translatable=\"false\">it's</string>\n" +
            "  <string name=\"b\">plain</string>\n</resources>"
        assertEquals(xml.replace("\"false\">it's<", "\"false\">it\\'s<"), escaper.processXml(xml))
    }

    @Test
    fun `documents this does not handle are left to an XML parser`() {
        assertNull(escaper.processXml("""<!DOCTYPE resources [<!ENTITY e "it's">]><resources><string name="a">&e;</string></resources>"""))
        assertNull(escaper.processXml("""<resources><string name="a">it's</resources>"""))
        assertNull(escaper.processXml("""<resources><string name="a">it's</b></resources>"""))
    }

    private fun assertSameStrings(processor: StringsXmlProcessor, xml: String) {
        val parsed = tempDir.resolve("parsed.xml").apply { writeText(xml) }
        processor.processFileWithParser(parsed)

        val scanned = tempDir.resolve("scanned.xml").apply { writeText(processor.processXml(xml)!!) }

        assertEquals(strings(parsed), strings(scanned), "Processed: ${scanned.readText()}")
    }

    /** The text of every string element, as an XML parser reads it. */
    private fun strings(file: File) = buildList {
        file.parseXml { parser ->
            var text: StringBuilder? = null
            var depth = 0
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "string" && text == null) {
                            text = StringBuilder()
                            depth = parser.depth
                        } else {
                            text?.append("<${parser.name}>")
                        }
                    }
                    XmlPullParser.TEXT -> text?.append("[${parser.text}]")
                    XmlPullParser.END_TAG -> if (text != null) {
                        if (parser.depth == depth) {
                            add(text.toString())
                            text = null
                        } else {
                            text.append("</${parser.name}>")
                        }
                    }
                }
            }
        }
    }

    private companion object {
        @JvmStatic
        fun documents() = listOf(
            """<resources><string name="a">it's "quoted" a\b</string></resources>""",
            """<resources><string name="a">it\'s \"quoted\" a\\b \n \t ← \z \u12</string></resources>""",
            """<resources><string name="a">"quoted whole"</string><string name="b">"</string></resources>""",
            "<resources><string name=\"a\">line 1\nline 2\ttab\r\nend</string></resources>",
            """<resources><string name="a">&lt;b&gt; &amp; &quot;x&quot; &apos;y&apos; &#39; &#x22; &#10;</string></resources>""",
            """<resources><string name="a">&quot;quoted by entities&quot;</string></resources>""",
            """<resources><string name="a">A <b>bold 'word'</b> and <i>"more"</i>.</string></resources>""",
            """<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2"><string name="a">Hi <xliff:g id="n" example="Bob's">%s</xliff:g>'s</string></resources>""",
            """<resources><string name="a">before <!-- it's --> after's</string></resources>""",
            """<resources><string name="a">"start <!-- c --> end"</string></resources>""",
            """<resources><string name="a">cdata <![CDATA[it's <b>raw</b>]]> text</string></resources>""",
            """<resources><string name="a">pi <?target it's?> text's</string></resources>""",
            """<resources><string name="a" note="a > b 'c'">it's</string><string name="b" note='"x"'>"q"</string></resources>""",
            """<resources><string name="a"/><string name="b"></string><string name="c" /></resources>""",
            """<resources><string-array name="arr"><item>it's</item></string-array><stringy name="x">it's</stringy><string name="a">it's</string></resources>""",
            """<resources><!-- <string name="c">it's</string> --><![CDATA[<string name="d">it's</string>]]><string name="a">it's</string></resources>""",
            """<resources><string name="a"><b><i>nested 'deep'</i> ok</b>'s</string></resources>""",
            """<resources><string name="a">it's</string ></resources>""",
            "<resources><string name=\"a\">ctl ${Char(1)} ${Char(0x7F)} 😀 ←</string></resources>",
            "<resources><string name=\"a\">\\" + "u0001 \\" + "uD83D\\" + "uDE00 \\" + "u003C &#92;'</string></resources>",
        )
    }
}
