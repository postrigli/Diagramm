package com.diagramm.storage

import com.diagramm.model.Node
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateFinderTest {
    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("diagramm-dups").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun `local duplicates are found by content, not by name`() = runTest {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val bigDifferentTail = big.copyOf().also { it[199_999] = 9 }
        File(tmp, "x/y").mkdirs()
        File(tmp, "orig.bin").writeBytes(big)
        File(tmp, "x/copy.bin").writeBytes(big)
        File(tmp, "x/y/other-name.bin").writeBytes(big)
        File(tmp, "same-size-diff-tail.bin").writeBytes(bigDifferentTail)
        File(tmp, "small1.txt").writeText("hello")
        File(tmp, "small2.txt").writeText("hello")
        File(tmp, "small3.txt").writeText("world")

        val tree = LocalScanner().scan(tmp)
        val groups = DuplicateFinder(LocalContentHasher()).find(tree)
        assertEquals(2, groups.size)
        assertEquals(3, groups[0].nodes.size)
        assertEquals(400_000, groups[0].wastedBytes)
        assertEquals(setOf("small1.txt", "small2.txt"), groups[1].nodes.map { it.name }.toSet())
    }

    @Test
    fun `cloud checksums are used without reading content`() = runTest {
        val tree = Node.directory(
            "/", "r",
            listOf(
                Node.file("1", "a", 100, checksum = "aaa"),
                Node.file("2", "b", 100, checksum = "aaa"),
                Node.file("3", "c", 100, checksum = "bbb"),
                Node.file("4", "d", 50, checksum = "aaa"),
            ),
        )
        val groups = DuplicateFinder(hasher = null).find(tree)
        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups[0].nodes.map { it.name }.toSet())
        assertTrue(groups[0].wastedBytes == 100L)
    }
}
