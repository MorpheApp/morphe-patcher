/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.util.ArrayDeque

internal fun NodeList.first(predicate: (Node) -> Boolean): Node {
    for (i in 0 until length) {
        val node = item(i)
        if (predicate(node)) {
            return node
        }
    }
    throw NoSuchElementException("Could not find element matching predicate")
}

internal fun NodeList.forEach(action: (Node) -> Unit) {
    for (i in 0 until length) {
        val node = item(i)
        action(node)
    }
}

internal fun NodeList.filter(predicate: (Node) -> Boolean): List<Node> {
    val result = mutableListOf<Node>()
    this.forEach {
        if (predicate(it)) {
            result.add(it)
        }
    }
    return result
}

internal fun <T> NodeList.map(action: (Node) -> T): List<T> {
    val result = mutableListOf<T>()
    this.forEach {
        result.add(action(it))
    }
    return result
}

internal fun <T> NodeList.mapNotNull(action: (Node) -> T?): List<T> {
    val result = mutableListOf<T>()
    this.forEach {
        val element = action(it)
        if (element != null) {
            result.add(element)
        }
    }
    return result
}

internal fun NodeList.forEachElement(action: (Element) -> Unit) {
    this.forEach {
        if (it is Element) {
            action(it)
        }
    }
}
