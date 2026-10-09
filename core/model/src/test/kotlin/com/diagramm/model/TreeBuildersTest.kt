package com.diagramm.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TreeBuildersTest {
    @Test
    fun `flat entries become a tree and orphans attach to root`() {
        val tree = FlatTreeBuilder.build(
            "root", "Drive",
            listOf(
                FlatEntry("f1", "Photos", "root", true),
                FlatEntry("a", "a.jpg", "f1", false, 100),
                FlatEntry("b", "b.jpg", "f1", false, 200),
                FlatEntry("c", "c.txt", "root", false, 5),
                FlatEntry("o", "orphan.bin", "missing-parent", false, 7),
            ),
        )
        assertEquals(312, tree.size)
        assertEquals(setOf("Photos", "c.txt", "orphan.bin"), tree.children.map { it.name }.toSet())
        assertEquals(300, tree.findById("f1")!!.size)
    }

    @Test
    fun `cycles do not hang and unreachable entries are dropped`() {
        val tree = FlatTreeBuilder.build(
            "root", "Drive",
            listOf(
                FlatEntry("a", "a", "b", true),
                FlatEntry("b", "b", "a", true),
                FlatEntry("f", "f.txt", "root", false, 1),
            ),
        )
        assertEquals(1, tree.size)
    }

    @Test
    fun `path entries create folders implicitly`() {
        val tree = PathTreeBuilder.build(
            "disk:/", "Yandex Disk",
            listOf(
                PathEntry("Photos/2024/a.jpg", 100),
                PathEntry("Photos/b.jpg", 50),
                PathEntry("doc.pdf", 10),
            ),
        )
        assertEquals(160, tree.size)
        val photos = tree.findById("disk:/Photos")
        assertNotNull(photos)
        assertEquals(150, photos.size)
        assertNotNull(tree.findById("disk:/Photos/2024/a.jpg"))
        assertEquals(listOf("Photos", "doc.pdf"), tree.children.map { it.name })
    }

    @Test
    fun `categories by extension and mime`() {
        assertEquals(FileCategory.VIDEO, FileCategorizer.categorize("clip.MP4"))
        assertEquals(FileCategory.IMAGE, FileCategorizer.categorize("noext", "image/png"))
        assertEquals(FileCategory.APP, FileCategorizer.categorize("x.apk"))
        assertEquals(FileCategory.OTHER, FileCategorizer.categorize("weird.zzz"))
        val tree = Node.directory("/", "r", listOf(Node.file("/a.mp4", "a.mp4", 10), Node.file("/b.jpg", "b.jpg", 5)))
        assertEquals(mapOf(FileCategory.VIDEO to 10L, FileCategory.IMAGE to 5L), FileCategorizer.totals(tree))
    }
}
