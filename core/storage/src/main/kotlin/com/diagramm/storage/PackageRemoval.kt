package com.diagramm.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

data class CommandResult(val exitCode: Int, val output: String)

/** Runs a command (argument list, no shell). Throws [IOException] if it cannot be started or times out. */
fun interface CommandRunner {
    suspend fun run(command: List<String>): CommandResult
}

data class PackageRemovalResult(
    /** Packages the command succeeded for. */
    val removed: Set<String>,
    /** package name -> short reason */
    val failures: Map<String, String>,
)

/**
 * Removes apps silently, all in one go, by running `pm uninstall <package>` through a [CommandRunner]
 * that executes with shell privileges (in the app: through Shizuku). The non-privileged route is the
 * system uninstaller driven by the UI - Android offers ordinary apps nothing that skips its
 * confirmation dialog.
 */
class PrivilegedPackageRemover(private val runner: CommandRunner) {

    /** `pm uninstall` for every package. */
    suspend fun remove(
        packages: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PackageRemovalResult = runPerPackage(packages, onProgress) { listOf("pm", "uninstall", it) }

    /**
     * Deletes only the cache of every package (`pm clear --cache-only`), leaving data and settings alone.
     * [PackageRemovalResult.removed] then holds the packages whose cache was cleared.
     */
    suspend fun clearCaches(
        packages: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PackageRemovalResult = runPerPackage(packages, onProgress) { listOf("pm", "clear", "--cache-only", it) }

    private suspend fun runPerPackage(
        packages: List<String>,
        onProgress: (done: Int, total: Int) -> Unit,
        command: (String) -> List<String>,
    ): PackageRemovalResult {
        val removed = LinkedHashSet<String>()
        val failures = LinkedHashMap<String, String>()
        for ((index, pkg) in packages.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!isValidPackageName(pkg)) {
                // defence in depth: only ever pass a plain package name to the command
                failures[pkg] = "Invalid package name"
            } else {
                try {
                    val r = runner.run(command(pkg))
                    if (r.exitCode == 0 && r.output.contains("Success")) removed.add(pkg) else failures[pkg] = reason(r)
                } catch (e: IOException) {
                    failures[pkg] = e.message ?: "command failed"
                }
            }
            onProgress(index + 1, packages.size)
        }
        return PackageRemovalResult(removed, failures)
    }

    private fun reason(r: CommandResult): String {
        // "Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]"
        FAILURE.find(r.output)?.let { return it.groupValues[1] }
        val text = r.output.trim()
        return if (text.isEmpty()) "no answer (access denied?)" else text.lineSequence().first().take(120)
    }

    companion object {
        private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val FAILURE = Regex("Failure \\[([^\\]]+)]")

        fun isValidPackageName(name: String): Boolean = NAME.matches(name)
    }
}
