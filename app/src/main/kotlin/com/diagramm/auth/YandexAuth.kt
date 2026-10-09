package com.diagramm.auth

import android.net.Uri
import com.diagramm.BuildConfig
import com.diagramm.data.SecureStore
import com.diagramm.net.AccessTokenProvider
import com.diagramm.net.AuthRequiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Yandex OAuth, "token" (implicit) flow: the browser is sent to oauth.yandex.ru and comes back to
 * `yx<client id>://token#access_token=...&expires_in=...`, which MainActivity forwards to
 * [handleRedirect]. The client id comes from the YANDEX_CLIENT_ID build setting.
 */
class YandexAuth(private val store: SecureStore) {
    val clientId: String = BuildConfig.YANDEX_CLIENT_ID
    val isConfigured: Boolean get() = clientId.isNotBlank()

    private val _connected = MutableStateFlow(token() != null)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val tokenProvider = AccessTokenProvider { token() ?: throw AuthRequiredException() }

    fun authorizeUri(): Uri = Uri.parse("https://oauth.yandex.ru/authorize").buildUpon()
        .appendQueryParameter("response_type", "token")
        .appendQueryParameter("client_id", clientId)
        .build()

    /** Returns true if [uri] was our OAuth redirect and carried a token. */
    fun handleRedirect(uri: Uri): Boolean {
        if (!isConfigured || uri.scheme != "yx$clientId" || uri.host != "token") return false
        val params = (uri.fragment ?: uri.query).orEmpty().split('&')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to Uri.decode(it[1]) } }
            .toMap()
        val token = params["access_token"] ?: return false
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: 0
        store.put(KEY_TOKEN, token)
        store.put(KEY_EXPIRES_AT, if (expiresIn > 0) (System.currentTimeMillis() + expiresIn * 1000).toString() else null)
        _connected.value = true
        return true
    }

    fun disconnect() {
        store.put(KEY_TOKEN, null)
        store.put(KEY_EXPIRES_AT, null)
        _connected.value = false
    }

    private fun token(): String? {
        val token = store.get(KEY_TOKEN) ?: return null
        val expiresAt = store.get(KEY_EXPIRES_AT)?.toLongOrNull()
        return if (expiresAt != null && expiresAt < System.currentTimeMillis()) null else token
    }

    private companion object {
        const val KEY_TOKEN = "yandex_token"
        const val KEY_EXPIRES_AT = "yandex_expires_at"
    }
}
