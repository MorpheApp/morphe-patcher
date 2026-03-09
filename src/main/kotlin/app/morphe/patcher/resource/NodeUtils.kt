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

internal fun Element.forEachAttribute(action: (Node) -> Unit) {
    val attributes = this.attributes ?: return
    for (i in 0 until attributes.length) {
        val attr = attributes.item(i)
        action(attr)
    }
}

internal fun Element.postOrderTraverse(action: (Element) -> Unit) {
    data class StackNode(val element: Element, var visited: Boolean = false)

    val stack = ArrayDeque<StackNode>()
    stack.add(StackNode(this))

    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        if (node.visited) {
            action(node.element)
            continue
        }

        node.visited = true
        stack.add(node) // Add back to process after children

        // Add children to stack
        val children = node.element.childNodes
        for (i in children.length - 1 downTo 0) {
            val child = children.item(i)
            if (child is Element) stack.add(StackNode(child))
        }
    }
}

internal fun Element.preOrderTraverse(action: (Element) -> Unit) {
    val stack = ArrayDeque<Element>()
    stack.add(this)

    while (stack.isNotEmpty()) {
        val elem = stack.removeLast()
        action(elem)

        // Add children to stack in reverse order to maintain order
        val children = elem.childNodes
        for (i in children.length - 1 downTo 0) {
            val child = children.item(i)
            if (child is Element) stack.add(child)
        }
    }
}
