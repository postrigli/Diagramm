package com.diagramm.data

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class LocalVolume(
    val label: String?,
    val root: File,
    val isPrimary: Boolean,
)

object LocalVolumes {
    /** Mounted shared-storage volumes: the internal "emulated" storage first, then SD cards / USB drives. */
    fun list(context: Context): List<LocalVolume> {
        val result = ArrayList<LocalVolume>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary.isDirectory) result.add(LocalVolume(null, primary, isPrimary = true))

        val manager = context.getSystemService(StorageManager::class.java)
        // getExternalFilesDirs gives one app-private dir per volume: <volume root>/Android/data/<pkg>/files
        for (dir in context.getExternalFilesDirs(null).orEmpty()) {
            if (dir == null) continue
            val path = dir.absolutePath
            val cut = path.indexOf("/Android/")
            if (cut <= 0) continue
            val root = File(path.substring(0, cut))
            if (root.absolutePath == primary.absolutePath || result.any { it.root == root }) continue
            if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) continue
            val label = manager?.getStorageVolume(root)?.getDescription(context)
            result.add(LocalVolume(label, root, isPrimary = false))
        }
        return result
    }
}

object StorageAccess {
    /** Whether we may read arbitrary files on shared storage. */
    fun has(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
}
