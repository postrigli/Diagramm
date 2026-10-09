package com.diagramm.storage

import com.diagramm.model.Node
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * A local folder or a whole volume.
 *
 * @param isVolumeRoot when true, the tree is completed with a "hidden space" node (used space we
 * could not see: system data, other users' data, Android/data...) and a "free space" node, so the
 * chart's full circle is the volume's capacity.
 * @param trash where [DeleteMode.TO_TRASH] puts things; null disables it.
 */
class LocalStorageProvider(
    override val id: String,
    override val displayName: String,
    private val root: File,
    private val isVolumeRoot: Boolean = true,
    private val trash: LocalTrash? = null,
    private val scanner: LocalScanner = LocalScanner(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StorageProvider {

    override suspend fun quota(): StorageQuota = withContext(dispatcher) {
        val total = root.totalSpace
        StorageQuota(totalBytes = total, usedBytes = (total - root.freeSpace).coerceAtLeast(0), trashBytes = trash?.totalSize() ?: 0)
    }

    override suspend fun scan(progress: ScanProgress): Node {
        progress.reset()
        val scanned = scanner.scan(root, progress)
        val tree = Node.directory(scanned.id, displayName, scanned.children, accessDenied = scanned.accessDenied)
        if (!isVolumeRoot) return tree
        val q = quota()
        val extra = ArrayList<Node>(2)
        val hidden = q.usedBytes - tree.size
        if (hidden > 0) extra.add(Node.hiddenSpace(hidden))
        val free = q.freeBytes ?: 0
        if (free > 0) extra.add(Node.freeSpace(free))
        return tree.plusChildren(extra)
    }

    override suspend fun delete(nodes: List<Node>, mode: DeleteMode): DeleteResult = withContext(dispatcher) {
        val deleted = HashSet<String>()
        val failures = LinkedHashMap<String, String>()
        val rootCanonical = root.canonicalFile
        for (node in nodes) {
            if (node.isSynthetic) {
                failures[node.id] = "Not a real file"
                continue
            }
            val file = File(node.id)
            try {
                val canonical = file.canonicalFile
                // never delete the root itself, nor anything outside the scanned root (e.g. via symlinks)
                if (canonical == rootCanonical || !canonical.toPath().startsWith(rootCanonical.toPath())) {
                    failures[node.id] = "Outside of the scanned folder"
                    continue
                }
                if (mode == DeleteMode.TO_TRASH) {
                    val t = trash ?: throw IOException("Trash is not available")
                    t.moveToTrash(file, node.size)
                } else {
                    deleteRecursively(file.toPath())
                }
                deleted.add(node.id)
            } catch (e: IOException) {
                failures[node.id] = e.message ?: e.javaClass.simpleName
            } catch (e: SecurityException) {
                failures[node.id] = e.message ?: "Permission denied"
            }
        }
        DeleteResult(deleted, failures)
    }

    private fun deleteRecursively(path: Path) {
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
