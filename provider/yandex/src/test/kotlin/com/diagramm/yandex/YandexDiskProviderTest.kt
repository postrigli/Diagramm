package com.diagramm.yandex

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
import kotlin.test.assertTrue

class YandexDiskProviderTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()
    private var pollsLeft = 1

    private val disk = """{"total_space":10000,"used_space":3000,"trash_size":500}"""
    private val page1 = """{"items":[
        {"name":"a.jpg","path":"disk:/Photos/2024/a.jpg","size":1000,"modified":"2024-03-04T11:22:33+00:00","mime_type":"image/jpeg","md5":"h1"},
        {"name":"b.jpg","path":"disk:/Photos/b.jpg","size":500}
    ]}"""
    private val page2 = """{"items":[{"name":"док.pdf","path":"disk:/Документы/док.pdf","size":700}]}"""

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val path = request.path.orEmpty()
                    return when {
                        path == "/v1/disk" -> MockResponse().setBody(disk)
                        path.startsWith("/v1/disk/resources/files") -> when {
                            "offset=0" in path -> MockResponse().setBody(page1)
                            "offset=2" in path -> MockResponse().setBody(page2)
                            else -> MockResponse().setBody("""{"items":[]}""")
                        }
                        request.method == "DELETE" && "Photos" in path && "2024" !in path ->
                            MockResponse().setResponseCode(202).setBody("""{"href":"${server.url("/v1/disk/operations/op1")}","method":"GET"}""")
                        request.method == "DELETE" -> MockResponse().setResponseCode(204)
                        path.startsWith("/v1/disk/operations/op1") -> {
                            val status = if (pollsLeft-- > 0) "in-progress" else "success"
                            MockResponse().setBody("""{"status":"$status"}""")
                        }
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

    private fun provider() = YandexDiskProvider(
        AuthorizedHttp(OkHttpClient(), AccessTokenProvider { "tok" }, scheme = "OAuth", baseDelayMillis = 0),
        apiBase = server.url("/v1/disk").toString().trimEnd('/'),
        pollDelayMillis = 0,
        pageSize = 2,
    )

    @Test
    fun `quota maps fields`() = runTest {
        val q = provider().quota()
        assertEquals(10000L, q.totalBytes)
        assertEquals(3000L, q.usedBytes)
        assertEquals(500L, q.trashBytes)
        assertEquals("OAuth tok", requests.first().getHeader("Authorization"))
    }

    @Test
    fun `scan builds the tree from flat paths across pages`() = runTest {
        val progress = ScanProgress()
        val tree = provider().scan(progress)
        assertEquals(1500, tree.findById("disk:/Photos")!!.size)
        assertEquals(1000, tree.findById("disk:/Photos/2024")!!.size)
        assertEquals(700, tree.findById("disk:/Документы")!!.size)
        assertEquals("h1", tree.findById("disk:/Photos/2024/a.jpg")!!.checksum)
        assertEquals("https://disk.yandex.ru/client/disk/Photos/2024", tree.findById("disk:/Photos/2024/a.jpg")!!.link)
        assertEquals("https://disk.yandex.ru/client/disk/%D0%94%D0%BE%D0%BA%D1%83%D0%BC%D0%B5%D0%BD%D1%82%D1%8B",
            tree.findById("disk:/Документы/док.pdf")!!.link)
        // 3000 used - 2200 scanned = 800 hidden (incl. 500 trash); free = 7000
        assertEquals(800, tree.children.first { it.kind == NodeKind.HIDDEN_SPACE }.size)
        assertEquals(7000, tree.children.first { it.kind == NodeKind.FREE_SPACE }.size)
        assertEquals(10000, tree.size)
        assertEquals(3, progress.files.get())
    }

    @Test
    fun `delete passes the permanently flag and waits for async folder operations`() = runTest {
        val p = provider()
        val tree = p.scan(ScanProgress())
        val photos = tree.findById("disk:/Photos")!!
        val file = tree.findById("disk:/Photos/2024/a.jpg")!!

        requests.clear()
        val r = p.delete(listOf(photos), DeleteMode.TO_TRASH)
        assertEquals(setOf("disk:/Photos"), r.deletedIds)
        val del = requests.first { it.method == "DELETE" }
        assertTrue("permanently=false" in del.path.orEmpty())
        assertTrue(requests.count { it.path.orEmpty().startsWith("/v1/disk/operations/op1") } >= 2)

        requests.clear()
        val r2 = p.delete(listOf(file), DeleteMode.PERMANENT)
        assertTrue(r2.isComplete)
        assertTrue("permanently=true" in requests.first { it.method == "DELETE" }.path.orEmpty())
        assertTrue(p.delete(listOf(tree), DeleteMode.PERMANENT).failures.isNotEmpty())
    }
}
