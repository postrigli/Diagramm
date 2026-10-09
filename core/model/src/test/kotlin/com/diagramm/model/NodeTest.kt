package com.diagramm.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeTest {
    private fun sample(): Node = Node.directory(
        "/", "root",
        listOf(
            Node.file("/a", "a", 10),
            Node.directory("/d", "d", listOf(Node.file("/d/x", "x", 500), Node.file("/d/y", "y", 300))),
            Node.file("/b", "b", 100),
        ),
    )

    @Test
    fun `folder size is sum of children and children are sorted biggest first`() {
        val root = sample()
        assertEquals(910, root.size)
        assertEquals(listOf("d", "b", "a"), root.children.map { it.name })
        assertEquals(listOf("x", "y"), root.children[0].children.map { it.name })
        assertEquals(4, root.fileCount)
    }

    @Test
    fun `parent pointers and path from root`() {
        val root = sample()
        val x = root.findById("/d/x")
        assertNotNull(x)
        assertEquals(listOf("root", "d", "x"), x.pathFromRoot().map { it.name })
        assertTrue(x.isDescendantOf(root))
    }

    @Test
    fun `removing drops nodes and recomputes sizes`() {
        val root = sample()
        val smaller = root.removing(setOf("/d/x", "/b"))
        assertEquals(310, smaller.size)
        assertEquals(listOf("d", "a"), smaller.children.map { it.name })
        assertEquals(300, smaller.children[0].size)
        assertSame(smaller, smaller.removing(setOf("nope")))
    }

    @Test
    fun `usedSize excludes free space`() {
        val root = sample().plusChildren(listOf(Node.freeSpace(1000), Node.hiddenSpace(50)))
        assertEquals(1960, root.size)
        assertEquals(960, root.usedSize)
        assertTrue(root.children.first { it.kind == NodeKind.FREE_SPACE }.isSynthetic)
    }
}
