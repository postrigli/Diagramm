package com.diagramm.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthorizedHttpTest {
    private lateinit var server: MockWebServer
    private val requestedRefresh = mutableListOf<Boolean>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun http(scheme: String = "Bearer") = AuthorizedHttp(
        OkHttpClient(),
        AccessTokenProvider { refresh ->
            requestedRefresh.add(refresh)
            if (refresh) "fresh" else "stale"
        },
        scheme = scheme,
        baseDelayMillis = 0,
    )

    @Test
    fun `sends the token with the configured scheme`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val r = http("OAuth").get(server.url("/x").toString())
        assertEquals(200, r.code)
        assertEquals("""{"ok":true}""", r.body)
        assertEquals("OAuth stale", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `refreshes the token once on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("done"))
        val r = http().get(server.url("/x").toString())
        assertEquals("done", r.body)
        assertEquals(listOf(false, true), requestedRefresh)
        server.takeRequest()
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `second 401 means sign-in required`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        assertFailsWith<AuthRequiredException> { http().get(server.url("/x").toString()) }
    }

    @Test
    fun `retries on 429 and 503`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("finally"))
        assertEquals("finally", http().get(server.url("/x").toString()).body)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `other errors surface as ApiException with body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val e = assertFailsWith<ApiException> { http().get(server.url("/x").toString()) }
        assertEquals(404, e.code)
        assertEquals("nope", e.body)
    }

    @Test
    fun `patch sends a json body`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        http().patchJson(server.url("/f").toString(), """{"trashed":true}""")
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("""{"trashed":true}""", req.body.readUtf8())
    }
}
