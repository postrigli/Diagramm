package com.diagramm.data

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import com.diagramm.R
import com.diagramm.model.Node
import com.diagramm.storage.AppUsage
import com.diagramm.storage.AppsTree
import com.diagramm.storage.AppsTreeLabels
import com.diagramm.storage.DeleteMode
import com.diagramm.storage.DeleteResult
import com.diagramm.storage.ScanProgress
import com.diagramm.storage.StorageProvider
import com.diagramm.storage.StorageQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** Android only reports other apps' sizes to apps that were granted "Usage access". */
class UsageAccessRequiredException : IOException("Usage access has not been granted")

object UsageAccess {
    fun has(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
}

/**
 * Installed apps as a tree: root -> app -> (app & libraries, data, cache), sizes from
 * [StorageStatsManager]. Removal goes through the system uninstaller (the activity), never through
 * [delete].
 */
class InstalledAppsProvider(private val context: Context) : StorageProvider {
    override val id: String = "apps"
    override val displayName: String = context.getString(R.string.source_apps)

    override suspend fun quota(): StorageQuota = StorageQuota(totalBytes = null, usedBytes = 0)

    @Suppress("DEPRECATION")
    override suspend fun scan(progress: ScanProgress): Node = withContext(Dispatchers.Default) {
        if (!UsageAccess.has(context)) throw UsageAccessRequiredException()
        progress.reset()
        val pm = context.packageManager
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()

        val usages = ArrayList<AppUsage>()
        for (info in pm.getInstalledApplications(0)) {
            ensureActive()
            val label = pm.getApplicationLabel(info).toString()
            progress.currentPath = label
            val s = try {
                stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, info.packageName, user)
            } catch (e: Exception) {
                continue // package vanished meanwhile, or the platform refuses this one
            }
            progress.files.incrementAndGet()
            progress.bytes.addAndGet(s.appBytes + s.dataBytes)
            usages.add(
                AppUsage(
                    packageName = info.packageName,
                    label = label,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    codeBytes = s.appBytes,
                    // the platform's data figure includes the cache; show the cache as its own part
                    dataBytes = (s.dataBytes - s.cacheBytes).coerceAtLeast(0),
                    cacheBytes = s.cacheBytes,
                ),
            )
        }
        AppsTree.build(
            usages,
            AppsTreeLabels(
                root = displayName,
                code = context.getString(R.string.apps_part_code),
                data = context.getString(R.string.apps_part_data),
                cache = context.getString(R.string.apps_part_cache),
            ),
        )
    }

    override suspend fun delete(nodes: List<Node>, mode: DeleteMode): DeleteResult =
        DeleteResult(emptySet(), nodes.associate { it.id to "Use the system uninstaller" })
}
