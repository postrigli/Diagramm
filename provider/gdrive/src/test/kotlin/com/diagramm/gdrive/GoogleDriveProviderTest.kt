package com.diagramm.gdrive

import com.diagramm.model.NodeKind
import com.diagramm.net.AccessTokenProvider
import com.diagramm.net.AuthorizedHttp
import com.diagramm.storage.DeleteMode
import com.diagramm.storage.ScanProgress
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoogleDriveProviderTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<String>()

    private val about = """{"storageQuota":{"limit":"15000","usage":"2200","usageInDrive":"1500","usageInDriveTrash":"100"}}"""
    private val page1 = """{"nextPageToken":"p2","files":[
        {"id":"F1","name":"Photos","mimeType":"application/vnd.google-apps.folder","parents":["ROOT"]},
        {"id":"A","name":"a.jpg","mimeType":"image/jpeg","size":"400","parents":["F1"],"md5Checksum":"m1","webViewLink":"https://drive/a"}
    ]}"""
    private val page2 = """{"files":[
        {"id":"B","name":"big.mp4","mimeType":"video/mp4","size":"700","parents":["ROOT"],"modifiedTime":"2024-05-01T10:00:00.000Z"},
        {"id":"DOC","name":"Notes","mimeType":"application/vnd.google-apps.document","parents":["ROOT"]},
        {"id":"SC","name":"shortcut","mimeType":"application/vnd.google-apps.shortcut","parents":["ROOT"]}
    ]}"""
    private val trashPage = """{"files":[{"id":"T1","name":"old.zip","mimeType":"application/zip","size":"100","parents":["ROOT"]}]}"""

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/drive/v3/about") -> MockResponse().setBody(about)
                        path.startsWith("/drive/v3/files/root") -> MockResponse().setBody("""{"id":"ROOT"}""")
                        path.startsWith("/drive/v3/files?") && "trashed%3Dtrue" in path -> MockResponse().setBody(trashPage)
                        path.startsWith("/drive/v3/files?") && "pageToken=p2" in path -> MockResponse().setBody(page2)
                        path.startsWith("/drive/v3/files?") -> MockResponse().setBody(page1)
                        request.method == "DELETE" -> MockResponse().setResponseCode(204)
                        request.method == "PATCH" -> MockResponse().setBody("{}")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun provider() = GoogleDriveProvider(
        AuthorizedHttp(OkHttpClient(), AccessTokenProvider { "tok" }, baseDelayMillis = 0),
        apiBase = server.url("/drive/v3").toString().trimEnd('/'),
    )

    @Test
    fun `quota maps limit usage and trash`() = runTest {
        val q = provider().quota()
        assertEquals(15000L, q.totalBytes)
        assertEquals(2200L, q.usedBytes)
        assertEquals(100L, q.trashBytes)
        assertEquals(12800L, q.freeBytes)
    }

    @Test
    fun `scan builds tree across pages with trash hidden and free space`() = runTest {
        val progress = ScanProgress()
        val tree = provider().scan(progress)
        val photos = tree.children.first { it.name == "Photos" }
        assertEquals(400, photos.size)
        assertEquals("https://drive/a", photos.children.single().link)
        assertEquals("m1", photos.children.single().checksum)
        assertTrue(tree.children.any { it.name == "big.mp4" && it.size == 700L })
        // native docs have no size, shortcuts are ignored
        assertTrue(tree.children.any { it.name == "Notes" && it.size == 0L })
        assertTrue(tree.children.none { it.name == "shortcut" })
        val trash = tree.children.first { it.id == GoogleDriveProvider.TRASH_ID }
        assertEquals(100, trash.size)
        // used 2200 - scanned (400+700+100) = 1000 hidden; free 15000-2200 = 12800
        assertEquals(1000, tree.children.first { it.kind == NodeKind.HIDDEN_SPACE }.size)
        assertEquals(12800, tree.children.first { it.kind == NodeKind.FREE_SPACE }.size)
        assertEquals(15000, tree.size)
        assertEquals(3, progress.files.get() - 1)
        assertNotNull(tree.findById("A"))
    }

    @Test
    fun `delete to trash patches, permanent deletes, trash items refuse trashing`() = runTest {
        val p = provider()
        val tree = p.scan(ScanProgress())
        val big = tree.children.first { it.name == "big.mp4" }
        val old = tree.findById("T1")!!

        requests.clear()
        val r1 = p.delete(listOf(big), DeleteMode.TO_TRASH)
        assertEquals(setOf("B"), r1.deletedIds)
        assertTrue("PATCH /drive/v3/files/B" in requests)

        val r2 = p.delete(listOf(big), DeleteMode.PERMANENT)
        assertTrue(r2.isComplete)
        assertTrue("DELETE /drive/v3/files/B" in requests)

        val r3 = p.delete(listOf(old), DeleteMode.TO_TRASH)
        assertEquals(setOf("T1"), r3.failures.keys)

        val trashNode = tree.children.first { it.id == GoogleDriveProvider.TRASH_ID }
        p.delete(listOf(trashNode), DeleteMode.PERMANENT)
        assertTrue("DELETE /drive/v3/files/trash" in requests)
    }
}
