package com.diagramm.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class CommandResult(val exitCode: Int, val output: String)

/** Runs a command (argument list, no shell). Throws if it cannot be started or times out. */
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
 * Removes apps / clears their cache silently, in one go, through a [CommandRunner] that executes with
 * shell privileges (in the app: through Shizuku). The non-privileged route for removal is the system
 * uninstaller driven by the UI - Android offers ordinary apps nothing that skips its confirmation dialog.
 *
 * Nothing here ever throws for a failed command: every problem is reported per package, so one bad app
 * (or a dying Shizuku) cannot take the app down.
 */
class PrivilegedPackageRemover(private val runner: CommandRunner) {

    /** `pm uninstall` for every package. */
    suspend fun remove(
        packages: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PackageRemovalResult {
        val removed = LinkedHashSet<String>()
        val failures = LinkedHashMap<String, String>()
        for ((index, pkg) in packages.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!isValidPackageName(pkg)) {
                failures[pkg] = "Invalid package name"
            } else {
                try {
                    val r = runner.run(listOf("pm", "uninstall", pkg))
                    if (r.exitCode == 0 && r.output.contains("Success")) removed.add(pkg) else failures[pkg] = reason(r.output)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures[pkg] = e.message ?: e.javaClass.simpleName
                }
            }
            onProgress(index + 1, packages.size)
        }
        return PackageRemovalResult(removed, failures)
    }

    /**
     * Deletes only the cache of every package (`pm clear --cache-only`), leaving data and settings alone.
     * [PackageRemovalResult.removed] then holds the packages whose cache was cleared.
     *
     * Packages are handled in batches: one shell process loops over [BATCH_SIZE] packages and reports
     * each result on its own line. Starting one process per app (hundreds of them) is slow and leaks
     * file descriptors, which is what used to crash the app on "select all".
     */
    suspend fun clearCaches(
        packages: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PackageRemovalResult {
        val removed = LinkedHashSet<String>()
        val failures = LinkedHashMap<String, String>()
        val total = packages.size
        var done = 0

        val (valid, invalid) = packages.partition { isValidPackageName(it) }
        for (pkg in invalid) failures[pkg] = "Invalid package name"
        done += invalid.size
        if (invalid.isNotEmpty()) onProgress(done, total)

        for (chunk in valid.chunked(BATCH_SIZE)) {
            currentCoroutineContext().ensureActive()
            try {
                val r = runner.run(listOf("sh", "-c", clearCacheScript(chunk)))
                val results = parseBatchOutput(r.output)
                for (pkg in chunk) {
                    val line = results[pkg]
                    when {
                        line == null -> failures[pkg] = reason(r.output)
                        line.isEmpty() -> removed.add(pkg)
                        else -> failures[pkg] = line
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                for (pkg in chunk) failures[pkg] = message
            }
            done += chunk.size
            onProgress(done, total)
        }
        return PackageRemovalResult(removed, failures)
    }

    private fun reason(output: String): String {
        // "Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]"
        FAILURE.find(output)?.let { return it.groupValues[1] }
        val text = output.trim()
        return if (text.isEmpty()) "no answer (access denied?)" else text.lineSequence().first().take(120)
    }

    companion object {
        const val BATCH_SIZE = 15

        private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val FAILURE = Regex("Failure \\[([^\\]]+)]")

        fun isValidPackageName(name: String): Boolean = NAME.matches(name)

        /**
         * The shell script for one batch. Package names were validated (letters, digits, `_` and `.`
         * only), so inlining them is safe. Always `--cache-only`: a bare `pm clear` would wipe user data.
         * Each package prints `OK|pkg` or `FAIL|pkg|first line of pm's answer`.
         */
        internal fun clearCacheScript(packages: List<String>): String =
            "for p in ${packages.joinToString(" ")}; do " +
                "r=\$(pm clear --cache-only \"\$p\" 2>&1); " +
                "case \"\$r\" in *Success*) echo \"OK|\$p\";; " +
                "*) echo \"FAIL|\$p|\$(printf %s \"\$r\" | head -n 1)\";; esac; done"

        /** package -> "" for success, or the failure reason. Lines that are not ours are ignored. */
        internal fun parseBatchOutput(output: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (line in output.lineSequence()) {
                val parts = line.trim().split('|', limit = 3)
                when {
                    parts.size >= 2 && parts[0] == "OK" -> result[parts[1]] = ""
                    parts.size == 3 && parts[0] == "FAIL" -> result[parts[1]] = parts[2].ifBlank { "failed" }
                }
            }
            return result
        }
    }
}
