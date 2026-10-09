package com.diagramm.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Supplies OAuth access tokens. [forceRefresh] is set after the server rejected the previous one. */
fun interface AccessTokenProvider {
    suspend fun accessToken(forceRefresh: Boolean): String
}

/** Thrown when the user must sign in (again) before the call can succeed. */
class AuthRequiredException(message: String = "Sign-in required") : IOException(message)

class ApiException(val code: Int, val body: String?, message: String = "HTTP $code") : IOException(message)

data class HttpResult(val code: Int, val body: String, val headers: Map<String, String>)

/**
 * Small authorised JSON-over-HTTP helper: adds the bearer header, refreshes the token once on 401
 * and backs off on 429/5xx (honouring Retry-After).
 */
class AuthorizedHttp(
    private val client: OkHttpClient,
    private val tokens: AccessTokenProvider,
    /** "Bearer" for Google, "OAuth" for Yandex. */
    private val scheme: String = "Bearer",
    private val maxAttempts: Int = 5,
    private val baseDelayMillis: Long = 500,
) {
    suspend fun get(url: String): HttpResult = execute { it.url(url).get() }

    suspend fun delete(url: String): HttpResult = execute { it.url(url).delete() }

    suspend fun patchJson(url: String, json: String): HttpResult =
        execute { it.url(url).patch(json.toRequestBody(JSON)) }

    suspend fun postJson(url: String, json: String): HttpResult =
        execute { it.url(url).post(json.toRequestBody(JSON)) }

    /** Sends the request built by [build]; 2xx returns, anything else throws [ApiException]. */
    suspend fun execute(build: (Request.Builder) -> Request.Builder): HttpResult {
        var refreshed = false
        var attempt = 0
        while (true) {
            attempt++
            val token = tokens.accessToken(refreshed)
            val request = build(Request.Builder()).header("Authorization", "$scheme $token").build()
            val response = client.newCall(request).await()
            val code = response.code
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val headers = response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
            val body = response.use { it.body?.string().orEmpty() }
            when {
                code in 200..299 -> return HttpResult(code, body, headers)
                code == 401 && !refreshed -> refreshed = true
                (code == 429 || code in 500..599) && attempt < maxAttempts -> {
                    val wait = retryAfter?.times(1000) ?: (baseDelayMillis * (1L shl (attempt - 1)))
                    delay(wait)
                }
                code == 401 -> throw AuthRequiredException()
                else -> throw ApiException(code, body)
            }
        }
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        },
    )
}
