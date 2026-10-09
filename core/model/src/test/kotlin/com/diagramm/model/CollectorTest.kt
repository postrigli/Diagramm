package com.diagramm.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorTest {
    private val root = Node.directory(
        "/", "root",
        listOf(
            Node.directory("/d", "d", listOf(Node.file("/d/x", "x", 500), Node.file("/d/y", "y", 300))),
            Node.file("/b", "b", 100),
            Node.freeSpace(9999),
        ),
    )
    private val d = root.findById("/d")!!
    private val x = root.findById("/d/x")!!
    private val y = root.findById("/d/y")!!
    private val b = root.findById("/b")!!

    @Test
    fun `total sums collected items`() {
        val c = Collector().plus(x).plus(b)
        assertEquals(600, c.totalSize)
        assertEquals(2, c.items.size)
    }

    @Test
    fun `adding a folder swallows collected descendants`() {
        val c = Collector().plus(x).plus(y).plus(d)
        assertEquals(listOf(d), c.items)
        assertEquals(800, c.totalSize)
    }

    @Test
    fun `adding inside a collected folder is a no-op`() {
        val c = Collector().plus(d)
        assertTrue(c.plus(x) === c)
        assertTrue(c.covers(x))
        assertFalse(c.covers(b))
    }

    @Test
    fun `synthetic nodes are rejected and toggle works`() {
        val free = root.children.first { it.kind == NodeKind.FREE_SPACE }
        assertTrue(Collector().plus(free).isEmpty)
        val c = Collector().toggle(b)
        assertEquals(setOf("/b"), c.ids)
        assertTrue(c.toggle(b).isEmpty)
    }

    @Test
    fun `tagged nodes cannot be collected`() {
        val app = Node.directory(
            "app:x", "X",
            listOf(Node.file("app:x#cache", "Cache", 10, tag = NodeTags.COMPONENT)),
        )
        val system = Node.directory("app:sys", "Sys", emptyList(), tag = NodeTags.SYSTEM_APP)
        val tree = Node.directory("/", "apps", listOf(app, system))
        assertTrue(Collector().plus(tree.findById("app:x#cache")!!).isEmpty)
        assertTrue(Collector().plus(tree.findById("app:sys")!!).isEmpty)
        assertEquals(1, Collector().plus(tree.findById("app:x")!!).items.size)
    }
}
