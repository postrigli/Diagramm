package com.diagramm.storage

import com.diagramm.model.Node
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class DuplicateGroup(val sizeBytes: Long, val nodes: List<Node>) {
    /** Bytes freed by keeping one copy. */
    val wastedBytes: Long get() = sizeBytes * (nodes.size - 1)
}

/** Reads file contents to compare them; null means "cannot read, skip this file". */
interface ContentHasher {
    suspend fun hash(node: Node, headOnly: Boolean): String?
}

/** SHA-256 over local files. The head-only variant hashes just the first [HEAD_BYTES]. */
class LocalContentHasher(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : ContentHasher {
    override suspend fun hash(node: Node, headOnly: Boolean): String? = withContext(dispatcher) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            File(node.id).inputStream().use { input ->
                val buf = ByteArray(128 * 1024)
                var remaining = if (headOnly) HEAD_BYTES else Long.MAX_VALUE
                while (remaining > 0) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    remaining -= n
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        const val HEAD_BYTES = 64L * 1024
    }
}

/**
 * Finds identical files: same size first (free), then - when the storage did not already report a
 * checksum - a cheap head hash, then a full hash only for what is still ambiguous.
 */
class DuplicateFinder(private val hasher: ContentHasher?) {

    suspend fun find(root: Node, minSizeBytes: Long = 1): List<DuplicateGroup> {
        val bySize = root.walk()
            .filter { it.isFile && it.size >= minSizeBytes }
            .groupBy { it.size }
            .filterValues { it.size > 1 }

        val result = ArrayList<DuplicateGroup>()
        for ((size, sameSize) in bySize) {
            coroutineContext.ensureActive()
            val withChecksum = sameSize.filter { it.checksum != null }
            if (withChecksum.size == sameSize.size) {
                withChecksum.groupBy { it.checksum }.values.filter { it.size > 1 }
                    .mapTo(result) { DuplicateGroup(size, it) }
                continue
            }
            val h = hasher ?: continue
            val headGroups = if (size > LocalContentHasher.HEAD_BYTES) {
                groupBy(sameSize) { h.hash(it, headOnly = true) }
            } else {
                listOf(sameSize)
            }
            for (candidates in headGroups) {
                if (candidates.size < 2) continue
                groupBy(candidates) { h.hash(it, headOnly = false) }
                    .filter { it.size > 1 }
                    .mapTo(result) { DuplicateGroup(size, it) }
            }
        }
        return result.sortedByDescending { it.wastedBytes }
    }

    private suspend fun groupBy(nodes: List<Node>, key: suspend (Node) -> String?): List<List<Node>> {
        val map = LinkedHashMap<String, MutableList<Node>>()
        for (n in nodes) {
            val k = key(n) ?: continue
            map.getOrPut(k) { ArrayList() }.add(n)
        }
        return map.values.toList()
    }
}
