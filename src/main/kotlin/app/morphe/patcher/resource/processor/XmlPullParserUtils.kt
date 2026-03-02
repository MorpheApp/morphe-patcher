package app.morphe.patcher.resource.processor

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer

internal fun XmlSerializer.copyNamespaces(parser: XmlPullParser) {
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

internal fun XmlSerializer.copyAttributes(
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
