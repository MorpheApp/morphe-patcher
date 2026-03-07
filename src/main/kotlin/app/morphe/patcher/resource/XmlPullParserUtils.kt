/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.File

val XPP_FACTORY = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }!!

fun File.parseXml(action: (XmlPullParser) -> Unit) {
    val parser = XPP_FACTORY.newPullParser()
    utf8Reader().use { reader ->
        parser.setInput(reader)
        action(parser)
    }
}

fun File.writeXml(action: (XmlSerializer) -> Unit) {
    val serializer = XPP_FACTORY.newSerializer()
    utf8Writer().use { writer ->
        serializer.setOutput(writer)
        action(serializer)
    }
}

/**
 * Skips the current tag and all of its nested content. This is useful for ignoring irrelevant tags.
 * The parser must be positioned at a START_TAG when calling this.
 * After this returns, the parser will be positioned at the corresponding END_TAG.
 */
fun XmlPullParser.skipTag() {
    if (eventType != XmlPullParser.START_TAG) {
        throw IllegalStateException("Expected START_TAG but was eventType=${eventType}")
    }
    var depth = 1
    while (depth != 0) {
        when (next()) {
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.START_TAG -> depth++
        }
    }
}

fun XmlPullParser.getAttributesAsMap(): Map<String, String> {
    val attributes = mutableMapOf<String, String>()
    for (i in 0 until attributeCount) {
        val namespace = getAttributeNamespace(i)
        val name = getAttributeName(i)
        val value = getAttributeValue(i)
        val key = if (namespace.isNullOrEmpty()) name else "$namespace:$name"
        attributes[key] = value
    }
    return attributes
}

/**
 * Copies all namespace declarations from the current depth of the parser to the serializer.
 * This should be called at the START_TAG event of a tag to ensure that any namespaces declared on that tag are included.
 */
fun XmlSerializer.copyNamespaces(parser: XmlPullParser) {
    // Declare namespace prefixes introduced at this depth
    val depth = parser.depth
    val nsStart = if (depth > 1) parser.getNamespaceCount(depth - 1) else 0
    val nsEnd = parser.getNamespaceCount(depth)
    for (i in nsStart until nsEnd) {
        setPrefix(
            parser.getNamespacePrefix(i) ?: "",
            parser.getNamespaceUri(i)
        )
    }
}

/**
 * Copies all attributes from the current START_TAG of the parser to the serializer.
 * The [attributeMapper] can be used to modify the namespace, name, or value of each attribute before copying.
 */
fun XmlSerializer.copyAttributes(
    parser: XmlPullParser,
    attributeMapper: (namespace: String?, name: String, value: String) -> Triple<String?, String, String> = { ns, name, value -> Triple(ns, name, value) }
) {
    for (i in 0 until parser.attributeCount) {
        val namespace = parser.getAttributeNamespace(i)
        val name = parser.getAttributeName(i)
        val value = parser.getAttributeValue(i)
        val updatedValues = attributeMapper(namespace, name, value)
        attribute(updatedValues.first, updatedValues.second, updatedValues.third)
    }
}
