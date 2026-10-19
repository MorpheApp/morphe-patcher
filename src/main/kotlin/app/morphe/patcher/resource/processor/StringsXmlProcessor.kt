/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.copyAttributes
import app.morphe.patcher.resource.copyNamespaces
import app.morphe.patcher.resource.parseXml
import app.morphe.patcher.resource.writeXml
import app.morphe.patcher.util.FileUtils.safelyMoveTo
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.logging.Logger

abstract internal class StringsXmlProcessor(
    internal val get: (String, String) -> File,
    internal val packageDirectories: Map<String, File>,
    internal val logString: String
) {
    private val logger = Logger.getLogger(this::class.java.name)

    /** Every strings file of every package. */
    fun stringsFiles(): List<File> = buildList {
        packageDirectories.forEach { (resPackageName, rootDir) ->
            rootDir.resolve("res").listFiles { it.isDirectory }?.forEach { dir ->
                // TODO Strings declared in arrays.xml may also need unescaping of string literals.
                dir.listFiles { it.name == "strings.xml" }?.forEach { file ->
                    val path = "res/${dir.name}/${file.name}"
                    logger.fine { "Processing $path" }
                    add(get(path, resPackageName))
                }
            }
        }
    }

    /**
     * @param files The strings files to process. Anything else in the collection is ignored.
     * @param log Whether to log that strings are processed, which decoding single files on request does not.
     */
    fun process(files: Collection<File> = stringsFiles(), log: Boolean = true) {
        if (log) logger.info(logString)
        files.filter { it.name == "strings.xml" && it.isFile }.parallelStream().forEach(::processFile)
    }

    private fun processFile(file: File) {
        val xml = file.readText(Charsets.UTF_8)
        val processed = processXml(xml)
        if (processed == null) {
            logger.fine { "Processing ${file.name} with an XML parser" }
            processFileWithParser(file)
        } else if (processed !== xml) {
            file.writeText(processed, Charsets.UTF_8)
        }
    }

    /**
     * Processes the text of every string element of [xml] by scanning the text of the document instead
     * of parsing and writing it again, which XML parsers and serializers are slow at on Android.
     * Only the text that [processString] changes is rewritten, so everything else of the document is
     * kept as it is, and a document without changes is not written at all.
     *
     * Text is processed as [processFileWithParser] processes it: the text of a string element and of
     * the elements nested in it, with each run of text between tags processed as one string.
     *
     * @return The processed document, the same instance if nothing changed, or null if the document
     * uses XML this does not handle, such as a document type declaration or an unknown entity.
     */
    internal fun processXml(xml: String): String? {
        if (xml.contains("<!DOCTYPE")) return null

        var result: StringBuilder? = null
        // End of the document text copied to the result so far.
        var copied = 0

        // Resolved text of a run that holds a comment, CDATA section or processing instruction,
        // which is null while the run is only text.
        var resolved: StringBuilder? = null

        /**
         * Processes the text from [start] to [end] as one string, resolved up to [position] if the
         * run holds more than text.
         *
         * @return False if the text has an entity this does not handle.
         */
        fun processRun(start: Int, position: Int, end: Int): Boolean {
            val text = resolved?.let { builder ->
                if (!appendResolved(builder, xml, position, end)) return false
                resolved = null
                builder.toString()
            } ?: xml.substring(start, end).let { raw ->
                if (raw.indexOf('&') < 0 && raw.indexOf('\r') < 0) return@let raw
                StringBuilder(raw.length).also { if (!appendResolved(it, raw, 0, raw.length)) return false }.toString()
            }

            val processed = processString(text)
            if (processed == text) return true

            val builder = result ?: StringBuilder(xml.length + 256).also { result = it }
            builder.append(xml, copied, start)
            appendEscaped(builder, processed)
            copied = end
            return true
        }

        var position = 0
        while (true) {
            val open = xml.indexOf('<', position)
            if (open < 0) break

            // Skip a comment, CDATA section or processing instruction, and any tag but a string start tag.
            val markupEnd = markupEnd(xml, open)
            if (markupEnd < 0) return null
            if (markupEnd > 0) {
                position = markupEnd
                continue
            }
            if (!isStringStartTag(xml, open)) {
                position = open + 1
                continue
            }
            position = tagEnd(xml, open)
            if (position < 0) return null
            // An empty string element.
            if (xml[position - 2] == '/') continue

            var depth = 0
            var runStart = position
            while (true) {
                // A string element without an end tag.
                val tagOpen = xml.indexOf('<', position)
                if (tagOpen < 0) return null

                val innerMarkupEnd = markupEnd(xml, tagOpen)
                if (innerMarkupEnd < 0) return null
                if (innerMarkupEnd > 0) {
                    // A comment, CDATA section or processing instruction is part of the text around it,
                    // as an XML parser reads it.
                    val builder = resolved ?: StringBuilder().also {
                        resolved = it
                        position = runStart
                    }
                    if (!appendResolved(builder, xml, position, tagOpen)) return null
                    if (xml.startsWith(CDATA_START, tagOpen)) {
                        builder.append(xml, tagOpen + CDATA_START.length, innerMarkupEnd - CDATA_END.length)
                    }
                    position = innerMarkupEnd
                    continue
                }

                val tagEnd = tagEnd(xml, tagOpen)
                if (tagEnd < 0) return null
                if (!processRun(runStart, position, tagOpen)) return null
                position = tagEnd
                runStart = position

                if (xml[tagOpen + 1] == '/') {
                    if (depth == 0) {
                        if (!isStringEndTag(xml, tagOpen)) return null
                        break
                    }
                    depth--
                } else if (xml[tagEnd - 2] != '/') {
                    depth++
                }
            }
        }

        val builder = result ?: return xml
        return builder.append(xml, copied, xml.length).toString()
    }

    internal fun processFileWithParser(file: File) {
        val tempFile = File(file.parentFile, file.name + ".tmp")

        file.parseXml { parser ->
            tempFile.writeXml { serializer ->
                serializer.startDocument("UTF-8", true)

                var eventType = parser.eventType
                var insideString = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {

                        XmlPullParser.START_TAG -> {
                            val tagName = parser.name

                            serializer.copyNamespaces(parser)
                            serializer.startTag(parser.namespace, tagName)
                            serializer.copyAttributes(parser)

                            if (tagName == "string") {
                                insideString = true
                            }
                        }

                        XmlPullParser.TEXT -> {
                            val text = parser.text
                            if (insideString) {
                                serializer.text(processString(text))
                            } else {
                                serializer.text(text)
                            }
                        }

                        XmlPullParser.END_TAG -> {
                            val tagName = parser.name
                            serializer.endTag(parser.namespace, tagName)

                            if (tagName == "string") {
                                insideString = false
                            }
                        }

                        XmlPullParser.CDSECT -> {
                            serializer.cdsect(parser.text)
                        }

                        XmlPullParser.COMMENT -> {
                            serializer.comment(parser.text)
                        }

                        XmlPullParser.IGNORABLE_WHITESPACE -> {
                            serializer.ignorableWhitespace(parser.text)
                        }
                    }

                    eventType = parser.next()
                }

                serializer.endDocument()
            }
        }

        // Replace original file
        tempFile.safelyMoveTo(file)
    }

    abstract fun processString(text: String): String

    private companion object {
        private const val STRING_TAG_NAME = "string"
        private const val CDATA_START = "<![CDATA["
        private const val CDATA_END = "]]>"

        /**
         * The end of the comment, CDATA section or processing instruction that starts at [open],
         * 0 if none starts there, or -1 if it does not end.
         */
        fun markupEnd(xml: String, open: Int): Int {
            val (start, end) = when {
                xml.startsWith("<!--", open) -> "<!--" to "-->"
                xml.startsWith(CDATA_START, open) -> CDATA_START to CDATA_END
                xml.startsWith("<?", open) -> "<?" to "?>"
                else -> return 0
            }
            val index = xml.indexOf(end, open + start.length)
            return if (index < 0) -1 else index + end.length
        }

        /**
         * The end of the tag that starts at [open], or -1 if it does not end. Attribute values can
         * hold any character but the quote around them.
         */
        fun tagEnd(xml: String, open: Int): Int {
            var index = open + 1
            while (index < xml.length) {
                val ch = xml[index]
                if (ch == '>') return index + 1
                if (ch == '"' || ch == '\'') {
                    index = xml.indexOf(ch, index + 1)
                    if (index < 0) return -1
                }
                index++
            }
            return -1
        }

        /** Whether the tag that starts at [open] is a string start tag or empty string element. */
        fun isStringStartTag(xml: String, open: Int) =
            xml.startsWith(STRING_TAG_NAME, open + 1) && endsTagName(xml, open + 1 + STRING_TAG_NAME.length)

        /** Whether the end tag that starts at [open] is a string end tag. */
        fun isStringEndTag(xml: String, open: Int) =
            xml.startsWith(STRING_TAG_NAME, open + 2) && endsTagName(xml, open + 2 + STRING_TAG_NAME.length)

        private fun endsTagName(xml: String, index: Int) =
            index < xml.length && xml[index].let { it == '>' || it == '/' || it.isWhitespace() }

        /**
         * Appends the text of [raw] from [start] to [end] with its entity and character references resolved,
         * and its line breaks normalized to a line feed as an XML parser normalizes them.
         *
         * @return False if it has an entity that is not predefined by XML.
         */
        fun appendResolved(builder: StringBuilder, raw: String, start: Int, end: Int): Boolean {
            var index = start
            while (true) {
                val ampersand = raw.indexOf('&', index)
                if (ampersand < 0 || ampersand >= end) {
                    appendNormalized(builder, raw, index, end)
                    return true
                }
                appendNormalized(builder, raw, index, ampersand)

                val semicolon = raw.indexOf(';', ampersand)
                if (semicolon < 0 || semicolon >= end) return false
                val name = raw.substring(ampersand + 1, semicolon)
                when (name) {
                    "lt" -> builder.append('<')
                    "gt" -> builder.append('>')
                    "amp" -> builder.append('&')
                    "quot" -> builder.append('"')
                    "apos" -> builder.append('\'')
                    else -> {
                        if (!name.startsWith('#')) return false
                        val code = if (name.startsWith("#x")) {
                            name.substring(2).toIntOrNull(16)
                        } else {
                            name.substring(1).toIntOrNull()
                        } ?: return false
                        if (code !in 0..Character.MAX_CODE_POINT) return false
                        builder.appendCodePoint(code)
                    }
                }
                index = semicolon + 1
            }
        }

        private fun appendNormalized(builder: StringBuilder, raw: String, start: Int, end: Int) {
            var index = start
            while (true) {
                val carriageReturn = raw.indexOf('\r', index)
                if (carriageReturn < 0 || carriageReturn >= end) {
                    builder.append(raw, index, end)
                    return
                }
                builder.append(raw, index, carriageReturn).append('\n')
                index = carriageReturn + 1
                if (index < end && raw[index] == '\n') index++
            }
        }

        /**
         * Appends [text] with the characters escaped that XML requires in text. Control characters,
         * which a parser would read as different characters or not at all, are character references.
         */
        fun appendEscaped(builder: StringBuilder, text: String) {
            for (ch in text) {
                when {
                    ch == '<' -> builder.append("&lt;")
                    ch == '>' -> builder.append("&gt;")
                    ch == '&' -> builder.append("&amp;")
                    ch == '\n' || ch == '\t' -> builder.append(ch)
                    ch.code < 0x20 -> builder.append("&#").append(ch.code).append(';')
                    else -> builder.append(ch)
                }
            }
        }
    }
}
