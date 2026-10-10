package com.diagramm.data

import android.content.Context
import com.diagramm.storage.CommandResult
import com.diagramm.storage.CommandRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** How apps are removed in the "Apps" section. */
enum class AppRemovalMode {
    /** Android's own uninstall dialog, one per app. Works everywhere. */
    SYSTEM_DIALOGS,

    /** `pm uninstall` through Shizuku: all selected apps at once, no dialogs. Needs Shizuku to be running. */
    SHIZUKU,
}

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var removalMode: AppRemovalMode
        get() = prefs.getString(KEY_MODE, null)?.let { name -> AppRemovalMode.entries.firstOrNull { it.name == name } }
            ?: AppRemovalMode.SYSTEM_DIALOGS
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).putBoolean(KEY_CHOSEN, true).apply()
        }

    /** False until the user answered the first-run question (or picked a mode in the settings). */
    val removalModeChosen: Boolean get() = prefs.getBoolean(KEY_CHOSEN, false)

    private companion object {
        const val KEY_MODE = "removal_mode"
        const val KEY_CHOSEN = "removal_mode_chosen"
    }
}

/**
 * Runs commands as child processes created by [start]. A command that outlives [timeoutMillis] is
 * killed. Both output streams are collected, so `pm`'s messages arrive whichever stream it uses.
 *
 * Every pipe of the process is closed explicitly: a Shizuku process holds three file descriptors,
 * and leaving them to the garbage collector exhausted the app's descriptor limit when hundreds of
 * processes were started in a row.
 */
class ProcessCommandRunner(
    private val timeoutMillis: Long = 120_000,
    private val start: (List<String>) -> Process,
) : CommandRunner {
    override suspend fun run(command: List<String>): CommandResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        DiagnosticLog.add("RUN " + command.joinToString(" ").take(400))
        try {
            val result = runOnce(command)
            DiagnosticLog.add(
                "  exit=${result.exitCode} in ${System.currentTimeMillis() - started} ms, output: " +
                    result.output.trim().ifEmpty { "(empty)" }.take(1500),
            )
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DiagnosticLog.add("  FAILED ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private suspend fun runOnce(command: List<String>): CommandResult {
        val process = start(command) // fails if it cannot be started
        try {
            quietly { process.outputStream.close() } // nothing to send; frees the pipe
            return coroutineScope {
                val out = async { process.inputStream.bufferedReader().use { it.readText() } }
                val err = async { process.errorStream.bufferedReader().use { it.readText() } }
                val text = withTimeoutOrNull(timeoutMillis) { out.await() + err.await() }
                if (text == null) {
                    quietly { process.destroy() } // closing the pipes unblocks the readers
                    out.cancel()
                    err.cancel()
                    throw IOException("Timed out waiting for ${command.first()}")
                }
                quietly { process.waitFor() }
                val exit = try {
                    process.exitValue()
                } catch (e: Exception) {
                    -1
                }
                CommandResult(exit, text)
            }
        } finally {
            quietly { process.destroy() } // may throw if the remote side is already gone
        }
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // cleanup must never turn a finished command into a crash
        }
    }
}
