package com.diagramm.storage

import com.diagramm.model.Node
import com.diagramm.model.NodeTags

/** Storage used by one installed app, as the platform reports it. */
data class AppUsage(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /** APK, native libraries and compiled code. */
    val codeBytes: Long,
    /** User data *excluding* the cache. */
    val dataBytes: Long,
    val cacheBytes: Long,
)

/** Localised names for the synthetic parts of the tree. */
data class AppsTreeLabels(val root: String, val code: String, val data: String, val cache: String)

/**
 * Turns per-app storage figures into a tree the chart can draw: root -> app -> (code, data, cache).
 * Kept free of Android classes so it can be unit tested.
 */
object AppsTree {
    private const val APP_PREFIX = "app:"

    fun appId(packageName: String) = "$APP_PREFIX$packageName"

    /** `app:com.example` and `app:com.example#cache` both map to `com.example`; anything else to null. */
    fun packageOf(nodeId: String): String? =
        if (nodeId.startsWith(APP_PREFIX)) nodeId.removePrefix(APP_PREFIX).substringBefore('#') else null

    fun build(apps: List<AppUsage>, labels: AppsTreeLabels): Node {
        val nodes = apps.mapNotNull { app ->
            val parts = listOf(
                Node.file("${appId(app.packageName)}#code", labels.code, app.codeBytes, tag = NodeTags.COMPONENT),
                Node.file("${appId(app.packageName)}#data", labels.data, app.dataBytes, tag = NodeTags.COMPONENT),
                Node.file("${appId(app.packageName)}#cache", labels.cache, app.cacheBytes, tag = NodeTags.COMPONENT),
            ).filter { it.size > 0 }
            if (parts.isEmpty()) {
                null
            } else {
                Node.directory(
                    appId(app.packageName), app.label.ifBlank { app.packageName }, parts,
                    tag = if (app.isSystem) NodeTags.SYSTEM_APP else null,
                )
            }
        }
        return Node.directory("apps:root", labels.root, nodes)
    }
}
