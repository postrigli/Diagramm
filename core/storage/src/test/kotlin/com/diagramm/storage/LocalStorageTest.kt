package com.diagramm.storage

import com.diagramm.model.Node
import com.diagramm.model.NodeKind
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalStorageTest {
    private lateinit var tmp: File
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("diagramm-test").toFile()
        root = File(tmp, "vol").apply { mkdirs() }
        File(root, "a").mkdirs()
        File(root, "a/one.bin").writeBytes(ByteArray(1000))
        File(root, "a/two.bin").writeBytes(ByteArray(250))
        File(root, "b/deep/er").mkdirs()
        File(root, "b/deep/er/three.bin").writeBytes(ByteArray(4000))
        File(root, "empty").mkdirs()
        File(root, "top.txt").writeBytes(ByteArray(10))
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun `scanner builds a tree with exact sizes`() = runTest {
        val progress = ScanProgress()
        val tree = LocalScanner().scan(root, progress)
        assertEquals(5260, tree.size)
        assertEquals(listOf("b", "a", "top.txt", "empty"), tree.children.map { it.name })
        assertEquals(1250, tree.children.first { it.name == "a" }.size)
        assertEquals(4, progress.files.get())
        assertEquals(5260, progress.bytes.get())
        assertTrue(progress.directories.get() >= 6)
    }

    @Test
    fun `symlinks are not followed or counted`() = runTest {
        val outside = File(tmp, "outside").apply { mkdirs() }
        File(outside, "huge.bin").writeBytes(ByteArray(100_000))
        try {
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            return@runTest
        }
        assertEquals(5260, LocalScanner().scan(root).size)
    }

    @Test
    fun `unreadable directory is flagged not fatal`() = runTest {
        val locked = File(root, "locked").apply { mkdirs() }
        File(locked, "x").writeBytes(ByteArray(5))
        if (!locked.setReadable(false) || locked.canRead()) return@runTest // running as root: cannot test
        try {
            val tree = LocalScanner().scan(root)
            assertTrue(tree.children.first { it.name == "locked" }.accessDenied)
        } finally {
            locked.setReadable(true)
        }
    }

    @Test
    fun `volume provider adds hidden and free space`() = runTest {
        val provider = LocalStorageProvider("local", "Internal", root, isVolumeRoot = true)
        val q = provider.quota()
        val tree = provider.scan(ScanProgress())
        val free = tree.children.firstOrNull { it.kind == NodeKind.FREE_SPACE }
        assertTrue(free != null && free.size > 0)
        val hidden = tree.children.firstOrNull { it.kind == NodeKind.HIDDEN_SPACE }?.size ?: 0
        // scanned files + hidden remainder + free == capacity (up to concurrent changes on the test machine)
        val scanned = tree.size - (hidden) - free.size
        assertEquals(5260, scanned)
        assertTrue(tree.size <= q.totalBytes!! + 1_000_000)
    }

    @Test
    fun `folder provider has no synthetic nodes`() = runTest {
        val provider = LocalStorageProvider("local", "Folder", root, isVolumeRoot = false)
        val tree = provider.scan(ScanProgress())
        assertEquals(5260, tree.size)
        assertTrue(tree.children.none { it.isSynthetic })
    }

    @Test
    fun `permanent delete removes files and reports ids`() = runTest {
        val provider = LocalStorageProvider("local", "Folder", root, isVolumeRoot = false)
        val tree = provider.scan(ScanProgress())
        val b = tree.children.first { it.name == "b" }
        val result = provider.delete(listOf(b), DeleteMode.PERMANENT)
        assertEquals(setOf(b.id), result.deletedIds)
        assertTrue(result.isComplete)
        assertFalse(File(root, "b").exists())
        assertEquals(1260, provider.scan(ScanProgress()).size)
    }

    @Test
    fun `trash moves, restores and empties`() = runTest {
        val trash = LocalTrash(File(root, ".trash"))
        val provider = LocalStorageProvider("local", "Folder", root, isVolumeRoot = false, trash = trash)
        val tree = provider.scan(ScanProgress())
        val a = tree.children.first { it.name == "a" }
        val result = provider.delete(listOf(a), DeleteMode.TO_TRASH)
        assertTrue(result.isComplete)
        assertFalse(File(root, "a").exists())
        val entries = trash.list()
        assertEquals(1, entries.size)
        assertEquals(1250, entries[0].sizeBytes)
        assertTrue(entries[0].isDirectory)

        trash.restore(entries[0])
        assertTrue(File(root, "a/one.bin").exists())
        assertTrue(trash.list().isEmpty())

        provider.delete(listOf(provider.scan(ScanProgress()).children.first { it.name == "a" }), DeleteMode.TO_TRASH)
        trash.empty()
        assertTrue(trash.list().isEmpty())
    }

    @Test
    fun `trash purges old items`() {
        var now = 1_000_000_000L
        val trash = LocalTrash(File(root, ".trash"), clock = { now })
        trash.moveToTrash(File(root, "top.txt"), 10)
        now += 40L * 24 * 3600_000
        File(root, "a/two.bin").let { trash.moveToTrash(it, 250) }
        assertEquals(1, trash.purgeOlderThan(30))
        assertEquals(listOf("two.bin"), trash.list().map { it.name })
    }

    @Test
    fun `refuses to delete the root or paths outside of it`() = runTest {
        val provider = LocalStorageProvider("local", "Folder", root, isVolumeRoot = false)
        val outside = File(tmp, "outside.txt").apply { writeText("x") }
        val result = provider.delete(
            listOf(Node.file(outside.path, "outside.txt", 1), Node.directory(root.path, "vol")),
            DeleteMode.PERMANENT,
        )
        assertTrue(result.deletedIds.isEmpty())
        assertEquals(2, result.failures.size)
        assertTrue(outside.exists())
        assertTrue(root.exists())
    }
}
