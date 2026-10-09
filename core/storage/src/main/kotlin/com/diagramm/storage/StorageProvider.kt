package com.diagramm.storage

import com.diagramm.model.Node
import java.util.concurrent.atomic.AtomicLong

/** Live counters a scan updates; the UI polls them. Safe to read from any thread. */
class ScanProgress {
    val files = AtomicLong()
    val directories = AtomicLong()
    val bytes = AtomicLong()

    @Volatile
    var currentPath: String = ""

    fun reset() {
        files.set(0)
        directories.set(0)
        bytes.set(0)
        currentPath = ""
    }
}

data class StorageQuota(
    /** Capacity in bytes, or null for unlimited. */
    val totalBytes: Long?,
    val usedBytes: Long,
    val trashBytes: Long = 0,
) {
    val freeBytes: Long? get() = totalBytes?.let { (it - usedBytes).coerceAtLeast(0) }
}

enum class DeleteMode {
    /** Move to a recoverable place: Diagramm's trash for local files, the cloud's own trash for clouds. */
    TO_TRASH,
    PERMANENT,
}

data class DeleteResult(
    val deletedIds: Set<String>,
    /** node id -> human readable reason */
    val failures: Map<String, String>,
) {
    val isComplete: Boolean get() = failures.isEmpty()
}

/**
 * A place whose space we can analyse and clean up: local volume, Google Drive, Yandex Disk...
 * Everything above this interface (chart, collector, UI) is storage agnostic.
 */
interface StorageProvider {
    val id: String
    val displayName: String

    suspend fun quota(): StorageQuota

    /** Reads the whole tree. Cancellable. Updates [progress] while running. */
    suspend fun scan(progress: ScanProgress): Node

    suspend fun delete(nodes: List<Node>, mode: DeleteMode): DeleteResult
}
