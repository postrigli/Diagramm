package com.diagramm.gdrive

import com.diagramm.model.FlatEntry
import com.diagramm.model.FlatTreeBuilder
import com.diagramm.model.Node
import com.diagramm.net.ApiException
import com.diagramm.net.AuthRequiredException
import com.diagramm.net.AuthorizedHttp
import com.diagramm.storage.DeleteMode
import com.diagramm.storage.DeleteResult
import com.diagramm.storage.ScanProgress
import com.diagramm.storage.StorageProvider
import com.diagramm.storage.StorageQuota
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.OffsetDateTime

/**
 * Google Drive through the REST API v3. Needs an access token with the
 * `https://www.googleapis.com/auth/drive` scope (listing needs only `drive.metadata.readonly`,
 * deleting needs the full scope).
 *
 * Drive does not report folder sizes, so the whole file list is downloaded (1000 per page, only the
 * handful of fields we need) and the tree is assembled locally.
 */
class GoogleDriveProvider(
    private val http: AuthorizedHttp,
    private val apiBase: String = "https://www.googleapis.com/drive/v3",
    override val displayName: String = "Google Drive",
) : StorageProvider {
    override val id: String = "gdrive"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun quota(): StorageQuota {
        val q = json.decodeFromString<About>(http.get("$apiBase/about?fields=storageQuota").body).storageQuota
        return StorageQuota(
            totalBytes = q.limit?.toLongOrNull(),
            usedBytes = q.usage?.toLongOrNull() ?: 0,
            trashBytes = q.usageInDriveTrash?.toLongOrNull() ?: 0,
        )
    }

    override suspend fun scan(progress: ScanProgress): Node {
        progress.reset()
        val quota = quota()
        val rootId = json.decodeFromString<DriveFile>(http.get("$apiBase/files/root?fields=id").body).id

        progress.currentPath = "My Drive"
        val live = listAll("trashed=false and 'me' in owners", progress)
        progress.currentPath = "Trash"
        val trashed = listAll("trashed=true and 'me' in owners", progress)

        val liveTree = FlatTreeBuilder.build(rootId, displayName, live)
        val children = ArrayList(liveTree.children)
        val trashTree = FlatTreeBuilder.build(TRASH_ID, "Trash", trashed)
        if (trashTree.children.isNotEmpty()) children.add(trashTree)

        val scanned = children.sumOf { it.size }
        val extra = ArrayList<Node>(2)
        val hidden = quota.usedBytes - scanned
        if (hidden > 0) extra.add(Node.hiddenSpace(hidden))
        val free = quota.freeBytes ?: 0
        if (free > 0) extra.add(Node.freeSpace(free))
        return Node.directory(rootId, displayName, children + extra)
    }

    private suspend fun listAll(query: String, progress: ScanProgress): List<FlatEntry> {
        val result = ArrayList<FlatEntry>()
        var pageToken: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val url = "$apiBase/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,parents,modifiedTime,md5Checksum,webViewLink)")
                .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
                .build()
            val page = json.decodeFromString<FileList>(http.get(url.toString()).body)
            for (f in page.files) {
                val isDir = f.mimeType == FOLDER_MIME
                // Shortcuts are pointers, not data.
                if (f.mimeType == SHORTCUT_MIME) continue
                val size = f.size?.toLongOrNull() ?: 0
                if (isDir) progress.directories.incrementAndGet() else {
                    progress.files.incrementAndGet()
                    progress.bytes.addAndGet(size)
                }
                result.add(
                    FlatEntry(
                        id = f.id,
                        name = f.name,
                        parentId = f.parents?.firstOrNull(),
                        isDirectory = isDir,
                        size = size,
                        modifiedMillis = parseTime(f.modifiedTime),
                        mimeType = f.mimeType,
                        checksum = f.md5Checksum,
                        link = f.webViewLink,
                    ),
                )
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    override suspend fun delete(nodes: List<Node>, mode: DeleteMode): DeleteResult {
        val deleted = HashSet<String>()
        val failures = LinkedHashMap<String, String>()
        for (node in nodes) {
            val inTrash = node.id == TRASH_ID || node.ancestors().any { it.id == TRASH_ID }
            try {
                when {
                    node.isSynthetic -> failures[node.id] = "Not a real file"
                    mode == DeleteMode.TO_TRASH && inTrash -> failures[node.id] = "Already in the trash"
                    node.id == TRASH_ID -> {
                        http.delete("$apiBase/files/trash")
                        deleted.add(node.id)
                    }
                    mode == DeleteMode.TO_TRASH -> {
                        http.patchJson("$apiBase/files/${node.id}", """{"trashed":true}""")
                        deleted.add(node.id)
                    }
                    else -> {
                        http.delete("$apiBase/files/${node.id}")
                        deleted.add(node.id)
                    }
                }
            } catch (e: ApiException) {
                // 404: already gone, which is what the user wanted
                if (e.code == 404) deleted.add(node.id) else failures[node.id] = describe(e)
            } catch (e: AuthRequiredException) {
                failures[node.id] = "Sign-in required"
            }
        }
        return DeleteResult(deleted, failures)
    }

    private fun describe(e: ApiException): String = when (e.code) {
        403 -> "No permission (HTTP 403)"
        else -> "HTTP ${e.code}"
    }

    private fun parseTime(s: String?): Long = try {
        if (s == null) 0 else OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        0
    }

    companion object {
        const val TRASH_ID = "gdrive:trash"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"
    }
}

@Serializable
internal data class About(val storageQuota: QuotaDto = QuotaDto())

@Serializable
internal data class QuotaDto(
    val limit: String? = null,
    val usage: String? = null,
    val usageInDrive: String? = null,
    val usageInDriveTrash: String? = null,
)

@Serializable
internal data class FileList(val nextPageToken: String? = null, val files: List<DriveFile> = emptyList())

@Serializable
internal data class DriveFile(
    val id: String,
    val name: String = "",
    val mimeType: String? = null,
    val size: String? = null,
    val parents: List<String>? = null,
    val modifiedTime: String? = null,
    val md5Checksum: String? = null,
    val webViewLink: String? = null,
)
