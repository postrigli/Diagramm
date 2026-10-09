package com.diagramm.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku
import java.io.IOException
import java.lang.reflect.InvocationTargetException

enum class ShizukuStatus {
    UNKNOWN,

    /** Neither the Shizuku app nor its binder is there. */
    NOT_INSTALLED,

    /** Installed, but the service has not been started (it must be started after every reboot). */
    NOT_RUNNING,

    /** Running; Diagramm has not been allowed to use it yet. */
    NEEDS_PERMISSION,
    READY,

    /** Shizuku older than v11, which has no runtime permission. */
    UNSUPPORTED,
}

/** Everything the app needs to know about, and ask of, Shizuku. */
class ShizukuAccess(private val context: Context) {

    fun status(): ShizukuStatus = try {
        when {
            !Shizuku.pingBinder() -> if (isShizukuAppInstalled()) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
            Shizuku.isPreV11() -> ShizukuStatus.UNSUPPORTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuStatus.READY
            else -> ShizukuStatus.NEEDS_PERMISSION
        }
    } catch (e: Throwable) {
        // the binder can die between the checks
        ShizukuStatus.NOT_RUNNING
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            // not running: the status refresh will say so
        }
    }

    /** Opens the Shizuku app so the user can start the service; falls back to its download page. */
    fun openShizukuApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        context.startActivity((launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openDownloadPage() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("DEPRECATION")
    private fun isShizukuAppInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val REQUEST_CODE = 4711
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }
}

/**
 * Starts a process with Shizuku's privileges (shell, or root when Shizuku was started with root).
 * Shizuku 13 keeps `newProcess` private, because its recommended route is a bound user service; for
 * one-shot `pm` commands that service would be a lot of machinery for no gain, so the method is
 * reached by reflection. If a future Shizuku API changes it, this fails with a clear [IOException]
 * and the caller falls back to the system uninstaller.
 */
object ShizukuShell {
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
    }

    fun start(command: List<String>): Process = try {
        newProcess.invoke(null, command.toTypedArray(), null, null) as Process
    } catch (e: InvocationTargetException) {
        throw IOException(e.targetException?.message ?: "Shizuku call failed", e.targetException)
    } catch (e: ReflectiveOperationException) {
        throw IOException("This Shizuku version is not supported", e)
    }
}
