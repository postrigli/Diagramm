package com.diagramm.auth

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.diagramm.data.SecureStore
import com.diagramm.net.AuthRequiredException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Google Drive access through the Google Identity "Authorization API": no client secret in the app,
 * the OAuth client is identified by package name + signing certificate (see README). Access tokens
 * are short lived and handed out by Play services on demand.
 */
class GoogleAuth(private val context: Context, private val store: SecureStore) {
    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
        .build()

    private val _connected = MutableStateFlow(store.get(KEY_CONNECTED) == "1")
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** A token without any UI; throws [AuthRequiredException] if the user has not granted access yet. */
    suspend fun silentToken(): String {
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        if (result.hasResolution()) throw AuthRequiredException("Google access has not been granted")
        return result.accessToken ?: throw AuthRequiredException("No access token")
    }

    /**
     * Starts the connection. Returns a [PendingIntent] for the consent screen the activity must launch,
     * or null if access was already granted.
     */
    suspend fun beginConnect(activity: Activity): PendingIntent? {
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        if (result.hasResolution()) return result.pendingIntent
        markConnected(result.accessToken != null)
        return null
    }

    /** Call with the data of the consent screen's result. */
    fun finishConnect(activity: Activity, data: Intent?) {
        val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        markConnected(result.accessToken != null)
    }

    fun disconnect() = markConnected(false)

    private fun markConnected(value: Boolean) {
        store.put(KEY_CONNECTED, if (value) "1" else null)
        _connected.value = value
    }

    private companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
        const val KEY_CONNECTED = "google_connected"
    }
}
