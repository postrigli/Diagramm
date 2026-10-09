package com.diagramm.yandex

import com.diagramm.model.Node
import com.diagramm.model.PathEntry
import com.diagramm.model.PathTreeBuilder
import com.diagramm.net.ApiException
import com.diagramm.net.AuthRequiredException
import com.diagramm.net.AuthorizedHttp
import com.diagramm.storage.DeleteMode
import com.diagramm.storage.DeleteResult
import com.diagramm.storage.ScanProgress
import com.diagramm.storage.StorageProvider
import com.diagramm.storage.StorageQuota
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder
import java.time.OffsetDateTime

/**
 * Yandex Disk through its REST API. The token needs `cloud_api:disk.info`, `cloud_api:disk.read`
 * and (for deleting) `cloud_api:disk.write`; requests use the `OAuth` authorisation scheme.
 *
 * Instead of walking folder by folder, the scan uses the flat "all files" listing and builds the
 * folder tree from the paths - one paged request stream instead of one request per folder.
 */
class YandexDiskProvider(
    private val http: AuthorizedHttp,
    private val apiBase: String = "https://cloud-api.yandex.net/v1/disk",
    override val displayName: String = "Yandex Disk",
    private val pollDelayMillis: Long = 1000,
    private val maxPolls: Int = 120,
    private val pageSize: Int = 1000,
) : StorageProvider {
    override val id: String = "yandex"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun quota(): StorageQuota {
        val d = json.decodeFromString<DiskInfo>(http.get(apiBase).body)
        return StorageQuota(totalBytes = d.totalSpace, usedBytes = d.usedSpace, trashBytes = d.trashSize)
    }

    override suspend fun scan(progress: ScanProgress): Node {
        progress.reset()
        val quota = quota()
        val entries = ArrayList<PathEntry>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val url = "$apiBase/resources/files".toHttpUrl().newBuilder()
                .addQueryParameter("limit", pageSize.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("fields", "items.name,items.path,items.size,items.modified,items.mime_type,items.md5")
                .build()
            val items = json.decodeFromString<FilesPage>(http.get(url.toString()).body).items
            if (items.isEmpty()) break
            for (item in items) {
                val rel = item.path.removePrefix("disk:").trim('/')
                if (rel.isEmpty()) continue
                progress.files.incrementAndGet()
                progress.bytes.addAndGet(item.size)
                progress.currentPath = rel
                entries.add(
                    PathEntry(
                        path = rel,
                        size = item.size,
                        modifiedMillis = parseTime(item.modified),
                        mimeType = item.mimeType,
                        checksum = item.md5,
                        link = webLink(rel.substringBeforeLast('/', "")),
                    ),
                )
            }
            offset += items.size
        }

        val tree = PathTreeBuilder.build(ROOT_ID, displayName, entries)
        val extra = ArrayList<Node>(2)
        // Includes the trash, which the flat listing does not show.
        val hidden = quota.usedBytes - tree.size
        if (hidden > 0) extra.add(Node.hiddenSpace(hidden))
        val free = quota.freeBytes ?: 0
        if (free > 0) extra.add(Node.freeSpace(free))
        return tree.plusChildren(extra)
    }

    override suspend fun delete(nodes: List<Node>, mode: DeleteMode): DeleteResult {
        val deleted = HashSet<String>()
        val failures = LinkedHashMap<String, String>()
        for (node in nodes) {
            if (node.isSynthetic || node.id == ROOT_ID) {
                failures[node.id] = "Cannot delete this item"
                continue
            }
            try {
                val url = "$apiBase/resources".toHttpUrl().newBuilder()
                    .addQueryParameter("path", node.id)
                    .addQueryParameter("permanently", (mode == DeleteMode.PERMANENT).toString())
                    .build()
                val response = http.delete(url.toString())
                if (response.code == 202) awaitOperation(response.body)
                deleted.add(node.id)
            } catch (e: ApiException) {
                if (e.code == 404) deleted.add(node.id) else failures[node.id] = "HTTP ${e.code}"
            } catch (e: AuthRequiredException) {
                failures[node.id] = "Sign-in required"
            } catch (e: OperationFailed) {
                failures[node.id] = e.message ?: "Operation failed"
            }
        }
        return DeleteResult(deleted, failures)
    }

    /** Folder deletion is asynchronous: the API answers 202 with a link to poll. */
    private suspend fun awaitOperation(body: String) {
        val href = json.decodeFromString<Link>(body).href
        repeat(maxPolls) {
            val status = json.decodeFromString<OperationStatus>(http.get(href).body).status
            when (status) {
                "success" -> return
                "failed" -> throw OperationFailed("Yandex Disk could not complete the operation")
                else -> delay(pollDelayMillis)
            }
        }
        throw OperationFailed("Timed out waiting for Yandex Disk")
    }

    private class OperationFailed(message: String) : Exception(message)

    private fun webLink(parentRelPath: String): String {
        val encoded = parentRelPath.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "https://disk.yandex.ru/client/disk" + if (encoded.isEmpty()) "" else "/$encoded"
    }

    private fun parseTime(s: String?): Long = try {
        if (s == null) 0 else OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        0
    }

    companion object {
        const val ROOT_ID = "disk:/"
    }
}

@Serializable
internal data class DiskInfo(
    @SerialName("total_space") val totalSpace: Long? = null,
    @SerialName("used_space") val usedSpace: Long = 0,
    @SerialName("trash_size") val trashSize: Long = 0,
)

@Serializable
internal data class FilesPage(val items: List<FileItem> = emptyList())

@Serializable
internal data class FileItem(
    val name: String = "",
    val path: String,
    val size: Long = 0,
    val modified: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val md5: String? = null,
)

@Serializable
internal data class Link(val href: String)

@Serializable
internal data class OperationStatus(val status: String = "")
