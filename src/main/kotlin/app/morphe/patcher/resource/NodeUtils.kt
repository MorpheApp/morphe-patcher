/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import app.morphe.patcher.util.Document
import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.w3c.dom.NodeList

fun NodeList.first(predicate: (Node) -> Boolean): Node {
    for (i in 0 until length) {
        val node = item(i)
        if (predicate(node)) {
            return node
        }
    }
    throw NoSuchElementException("Could not find element matching predicate")
}

fun NodeList.forEach(action: (Node) -> Unit) {
    for (i in 0 until length) {
        val node = item(i)
        action(node)
    }
}

fun NodeList.filter(predicate: (Node) -> Boolean): List<Node> {
    val result = mutableListOf<Node>()
    this.forEach {
        if (predicate(it)) {
            result.add(it)
        }
    }
    return result
}

fun <T> NodeList.map(action: (Node) -> T): List<T> {
    val result = mutableListOf<T>()
    this.forEach {
        result.add(action(it))
    }
    return result
}

fun <T> NodeList.mapNotNull(action: (Node) -> T?): List<T> {
    val result = mutableListOf<T>()
    this.forEach {
        val element = action(it)
        if (element != null) {
            result.add(element)
        }
    }
    return result
}

fun NodeList.forEachElement(action: (Element) -> Unit) {
    this.forEach {
        if (it is Element) {
            action(it)
        }
    }
}

fun Element.forEachAttribute(action: (Node) -> Unit) {
    val attributes = this.attributes ?: return
    for (i in 0 until attributes.length) {
        val attr = attributes.item(i)
        action(attr)
    }
}

fun Element.postOrderTraverse(op: (Element) -> Unit) {
    for (i in 0 until childNodes.length) {
        val child = childNodes.item(i) as? Element
        child?.postOrderTraverse(op)
    }
    op(this)
}

fun Document.inOrderTraverse(op: (Element) -> Unit) {
    val deque = ArrayDeque<Element>()
    for (i in 0 until childNodes.length) {
        val childElem = childNodes.item(i) as? Element
        if (childElem != null) {
            deque.add(childElem)
        }
    }
    while (deque.isNotEmpty()) {
        val element = deque.removeFirst()
        for (i in 0 until element.childNodes.length) {
            val childElem = element.childNodes.item(i) as? Element
            if (childElem != null) {
                deque.add(childElem)
            }
        }

        op(element)
    }
}
