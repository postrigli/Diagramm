package com.diagramm.storage

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

data class TrashEntry(
    val id: String,
    val originalPath: String,
    val name: String,
    val sizeBytes: Long,
    val deletedAtMillis: Long,
    val isDirectory: Boolean,
)

/**
 * Diagramm's own recycle bin. Android has none, so "delete" would be irreversible. Items are
 * *renamed* into [root] (an atomic move on the same volume - instant, no copying), together with a
 * small metadata file so they can be restored. Space is only freed once the trash is emptied.
 */
class LocalTrash(val root: File, private val clock: () -> Long = System::currentTimeMillis) {

    /** Moves [file] into the trash. Throws [IOException] if it lives on another volume than the trash. */
    fun moveToTrash(file: File, sizeBytes: Long): TrashEntry {
        val id = "${clock()}-${UUID.randomUUID().toString().take(8)}"
        val slot = File(root, id)
        if (!slot.mkdirs()) throw IOException("Cannot create ${slot.path}")
        try {
            val payload = File(slot, PAYLOAD)
            try {
                Files.move(file.toPath(), payload.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                throw IOException("Trash is on a different volume", e)
            }
            val entry = TrashEntry(id, file.absolutePath, file.name, sizeBytes, clock(), payload.isDirectory)
            writeMeta(slot, entry)
            return entry
        } catch (e: Exception) {
            slot.deleteRecursively()
            throw e
        }
    }

    fun list(): List<TrashEntry> =
        root.listFiles()?.mapNotNull { readMeta(it) }?.sortedByDescending { it.deletedAtMillis } ?: emptyList()

    fun totalSize(): Long = list().sumOf { it.sizeBytes }

    /** Puts the item back where it came from. Fails if something is already there. */
    fun restore(entry: TrashEntry) {
        val slot = File(root, entry.id)
        val target = File(entry.originalPath)
        if (target.exists()) throw IOException("${target.path} already exists")
        target.parentFile?.mkdirs()
        Files.move(File(slot, PAYLOAD).toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        slot.deleteRecursively()
    }

    fun purge(entry: TrashEntry) {
        File(root, entry.id).deleteRecursively()
    }

    fun empty() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Permanently removes items trashed more than [days] days ago; returns how many. */
    fun purgeOlderThan(days: Int): Int {
        val limit = clock() - days * 24L * 3600_000L
        val old = list().filter { it.deletedAtMillis < limit }
        old.forEach { purge(it) }
        return old.size
    }

    private fun writeMeta(slot: File, e: TrashEntry) {
        val p = Properties()
        p["originalPath"] = e.originalPath
        p["name"] = e.name
        p["size"] = e.sizeBytes.toString()
        p["deletedAt"] = e.deletedAtMillis.toString()
        p["dir"] = e.isDirectory.toString()
        File(slot, META).outputStream().use { p.store(it, null) }
    }

    private fun readMeta(slot: File): TrashEntry? {
        val meta = File(slot, META)
        if (!meta.isFile || !File(slot, PAYLOAD).exists()) return null
        return try {
            val p = Properties().apply { meta.inputStream().use { load(it) } }
            TrashEntry(
                id = slot.name,
                originalPath = p.getProperty("originalPath") ?: return null,
                name = p.getProperty("name") ?: return null,
                sizeBytes = p.getProperty("size")?.toLongOrNull() ?: 0,
                deletedAtMillis = p.getProperty("deletedAt")?.toLongOrNull() ?: 0,
                isDirectory = p.getProperty("dir") == "true",
            )
        } catch (e: IOException) {
            null
        }
    }

    private companion object {
        const val PAYLOAD = "payload"
        const val META = "meta.properties"
    }
}
