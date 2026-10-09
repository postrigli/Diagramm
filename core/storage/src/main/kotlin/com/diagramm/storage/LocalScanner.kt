package com.diagramm.storage

import com.diagramm.model.Node
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Walks a directory tree with one `stat` per entry. Symlinks are not followed (and not counted), so
 * nothing is counted twice and loops are impossible. The first levels are scanned in parallel.
 */
class LocalScanner(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Directories at depth < this are scanned concurrently, deeper ones sequentially. */
    private val parallelDepth: Int = 2,
) {
    suspend fun scan(root: File, progress: ScanProgress = ScanProgress()): Node = withContext(dispatcher) {
        val job = currentCoroutineContext().job
        coroutineScope { scanDir(root.toPath(), root.name.ifEmpty { root.path }, 0, progress, job) }
    }

    private suspend fun scanDir(dir: Path, name: String, depth: Int, progress: ScanProgress, job: Job): Node {
        currentCoroutineContext().ensureActive()
        progress.currentPath = dir.toString()
        progress.directories.incrementAndGet()

        val files = ArrayList<Node>()
        val subdirs = ArrayList<Path>()
        val denied = !listInto(dir, files, subdirs, progress)

        val dirNodes: List<Node> = if (depth < parallelDepth && subdirs.size > 1) {
            coroutineScope {
                subdirs.map { sub ->
                    async(dispatcher) { scanDir(sub, sub.fileName.toString(), depth + 1, progress, job) }
                }.awaitAll()
            }
        } else {
            subdirs.map { sub -> scanDirBlocking(sub, sub.fileName.toString(), progress, job) }
        }
        return Node.directory(dir.toString(), name, dirNodes + files, accessDenied = denied)
    }

    private fun scanDirBlocking(dir: Path, name: String, progress: ScanProgress, job: Job): Node {
        job.ensureActive()
        progress.currentPath = dir.toString()
        progress.directories.incrementAndGet()
        val files = ArrayList<Node>()
        val subdirs = ArrayList<Path>()
        val denied = !listInto(dir, files, subdirs, progress)
        val dirNodes = subdirs.map { scanDirBlocking(it, it.fileName.toString(), progress, job) }
        return Node.directory(dir.toString(), name, dirNodes + files, accessDenied = denied)
    }

    /** Fills [files] and [subdirs]; returns false if the directory could not be read. */
    private fun listInto(dir: Path, files: MutableList<Node>, subdirs: MutableList<Path>, progress: ScanProgress): Boolean {
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (entry in stream) {
                    val attrs = try {
                        Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (e: IOException) {
                        continue
                    } catch (e: SecurityException) {
                        continue
                    }
                    when {
                        attrs.isSymbolicLink -> Unit
                        attrs.isDirectory -> subdirs.add(entry)
                        attrs.isRegularFile -> {
                            val size = attrs.size()
                            progress.files.incrementAndGet()
                            progress.bytes.addAndGet(size)
                            files.add(
                                Node.file(entry.toString(), entry.fileName.toString(), size, attrs.lastModifiedTime().toMillis()),
                            )
                        }
                    }
                }
            }
            return true
        } catch (e: IOException) {
            return false
        } catch (e: SecurityException) {
            return false
        }
    }
}
