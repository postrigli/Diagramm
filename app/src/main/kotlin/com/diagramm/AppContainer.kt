package com.diagramm

import android.content.Context
import com.diagramm.auth.GoogleAuth
import com.diagramm.auth.YandexAuth
import com.diagramm.data.AppSettings
import com.diagramm.data.AppsSummaryStore
import com.diagramm.data.InstalledAppsProvider
import com.diagramm.data.LocalVolume
import com.diagramm.data.LocalVolumes
import com.diagramm.data.SecureStore
import com.diagramm.gdrive.GoogleDriveProvider
import com.diagramm.net.AccessTokenProvider
import com.diagramm.net.AuthorizedHttp
import com.diagramm.storage.LocalStorageProvider
import com.diagramm.storage.LocalTrash
import com.diagramm.storage.StorageProvider
import com.diagramm.yandex.YandexDiskProvider
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency container: the app is small enough that a DI framework would only add noise. */
class AppContainer(private val context: Context) {
    val secureStore = SecureStore(context)
    val settings = AppSettings(context)
    val appsSummary = AppsSummaryStore(context)
    val googleAuth = GoogleAuth(context, secureStore)
    val yandexAuth = YandexAuth(secureStore)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun localVolumes(): List<LocalVolume> = LocalVolumes.list(context)

    fun trashFor(volumeRoot: File): LocalTrash = LocalTrash(File(volumeRoot, TRASH_DIR_NAME))

    fun localProvider(id: String, name: String, root: File): StorageProvider =
        LocalStorageProvider(id = id, displayName = name, root = root, isVolumeRoot = true, trash = trashFor(root))

    fun appsProvider(): InstalledAppsProvider = InstalledAppsProvider(context)


    fun googleProvider(): StorageProvider = GoogleDriveProvider(
        AuthorizedHttp(httpClient, AccessTokenProvider { googleAuth.silentToken() }),
        displayName = context.getString(R.string.source_gdrive),
    )

    fun yandexProvider(): StorageProvider = YandexDiskProvider(
        AuthorizedHttp(httpClient, yandexAuth.tokenProvider, scheme = "OAuth"),
        displayName = context.getString(R.string.source_yandex),
    )

    companion object {
        const val TRASH_DIR_NAME = ".Diagramm-Trash"
    }
}
