package com.diagramm.storage

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivilegedPackageRemoverTest {
    private class FakeRunner(val answer: (List<String>) -> CommandResult) : CommandRunner {
        val commands = mutableListOf<List<String>>()
        override suspend fun run(command: List<String>): CommandResult {
            commands.add(command)
            return answer(command)
        }
    }

    @Test
    fun `removes every package with pm uninstall`() = runTest {
        val runner = FakeRunner { CommandResult(0, "Success\n") }
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = PrivilegedPackageRemover(runner).remove(listOf("com.a.app", "org.b.tool")) { d, t -> progress.add(d to t) }
        assertEquals(setOf("com.a.app", "org.b.tool"), result.removed)
        assertTrue(result.failures.isEmpty())
        assertEquals(listOf("pm", "uninstall", "com.a.app"), runner.commands[0])
        assertEquals(listOf(1 to 2, 2 to 2), progress)
    }

    @Test
    fun `failure reason is taken from the pm output`() = runTest {
        val runner = FakeRunner { cmd ->
            if (cmd.last() == "com.bad.app") CommandResult(1, "Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n")
            else CommandResult(0, "Success")
        }
        val result = PrivilegedPackageRemover(runner).remove(listOf("com.ok.app", "com.bad.app"))
        assertEquals(setOf("com.ok.app"), result.removed)
        assertEquals(mapOf("com.bad.app" to "DELETE_FAILED_DEVICE_POLICY_MANAGER"), result.failures)
    }

    @Test
    fun `silent and failing commands are reported per package`() = runTest {
        val silent = PrivilegedPackageRemover(FakeRunner { CommandResult(1, "") }).remove(listOf("com.x.y"))
        assertTrue(silent.failures.getValue("com.x.y").contains("access denied"))
        val broken = PrivilegedPackageRemover(FakeRunner { throw IOException("Shizuku went away") }).remove(listOf("com.x.y"))
        assertEquals("Shizuku went away", broken.failures["com.x.y"])
    }

    @Test
    fun `remove survives exceptions that are not IOExceptions`() = runTest {
        val runner = FakeRunner { throw RuntimeException("DeadObjectException") }
        val result = PrivilegedPackageRemover(runner).remove(listOf("com.x.y"))
        assertEquals("DeadObjectException", result.failures["com.x.y"])
    }

    @Test
    fun `only plain package names are ever executed`() = runTest {
        val runner = FakeRunner { CommandResult(0, "Success") }
        val evil = listOf("com.a.b; rm -rf /", "x", "com.a.b && reboot", "\$(id).a", "com.a.b\nid", "")
        val result = PrivilegedPackageRemover(runner).remove(evil)
        assertTrue(runner.commands.isEmpty())
        assertTrue(result.removed.isEmpty())
        assertEquals(evil.toSet(), result.failures.keys)
    }

    @Test
    fun `package name validation`() {
        assertTrue(PrivilegedPackageRemover.isValidPackageName("com.google.android.apps.maps"))
        assertTrue(PrivilegedPackageRemover.isValidPackageName("org.example.app_2"))
        assertFalse(PrivilegedPackageRemover.isValidPackageName("single"))
        assertFalse(PrivilegedPackageRemover.isValidPackageName("1com.example"))
        assertFalse(PrivilegedPackageRemover.isValidPackageName("com..example"))
    }

    /** Plays the role of the shell: answers the batch script the way pm would. */
    private fun fakeShell(failing: Set<String> = emptySet(), unknownOption: Set<String> = emptySet()) = FakeRunner { cmd ->
        val script = cmd.last()
        val pkgs = Regex("for p in (.*?); do").find(script)!!.groupValues[1].split(' ')
        CommandResult(
            0,
            pkgs.joinToString("\n") { p ->
                when (p) {
                    in failing -> "FAIL|$p|Failure [CLEAR_FAILED]"
                    in unknownOption -> "FAIL|$p|Error: Unknown option --cache-only"
                    else -> "OK|$p"
                }
            },
        )
    }

    @Test
    fun `clearCaches runs batches through sh and never touches data`() = runTest {
        val runner = fakeShell(failing = setOf("com.bad.app"))
        val result = PrivilegedPackageRemover(runner).clearCaches(listOf("com.ok.app", "com.bad.app", "bad name; reboot"))
        assertEquals(setOf("com.ok.app"), result.removed)
        assertEquals("Failure [CLEAR_FAILED]", result.failures["com.bad.app"])
        assertTrue("bad name; reboot" in result.failures)
        assertEquals(1, runner.commands.size)
        assertEquals(listOf("sh", "-c"), runner.commands[0].take(2))
        val script = runner.commands[0].last()
        assertTrue("pm clear --cache-only" in script)
        assertFalse(Regex("pm clear (?!--cache-only)").containsMatchIn(script)) // a bare `pm clear` wipes user data
        assertFalse("reboot" in script) // the invalid name never reached the shell
    }

    @Test
    fun `many packages become few processes with steady progress`() = runTest {
        val packages = (1..40).map { "com.example.app$it" }
        val runner = fakeShell()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = PrivilegedPackageRemover(runner).clearCaches(packages) { d, t -> progress.add(d to t) }
        assertEquals(packages.toSet(), result.removed)
        // 40 apps, 15 per process: 3 processes instead of 40 (each process used to leak file descriptors)
        assertEquals(3, runner.commands.size)
        assertEquals(listOf(15 to 40, 30 to 40, 40 to 40), progress)
    }

    @Test
    fun `a failing batch is reported per package and does not stop the others`() = runTest {
        var call = 0
        val runner = FakeRunner { cmd ->
            if (call++ == 0) throw IllegalStateException("Shizuku binder is dead") // not an IOException
            val pkgs = Regex("for p in (.*?); do").find(cmd.last())!!.groupValues[1].split(' ')
            CommandResult(0, pkgs.joinToString("\n") { "OK|$it" })
        }
        val packages = (1..20).map { "com.example.app$it" }
        val result = PrivilegedPackageRemover(runner).clearCaches(packages)
        assertEquals(packages.drop(15).toSet(), result.removed)
        assertEquals(packages.take(15).toSet(), result.failures.keys)
        assertEquals("Shizuku binder is dead", result.failures["com.example.app1"])
    }

    @Test
    fun `packages the shell never mentioned count as failed`() = runTest {
        val runner = FakeRunner { CommandResult(1, "") }
        val result = PrivilegedPackageRemover(runner).clearCaches(listOf("com.a.b"))
        assertTrue(result.removed.isEmpty())
        assertTrue(result.failures.getValue("com.a.b").contains("access denied"))
    }

    @Test
    fun `batch output parsing ignores noise`() {
        val out = "WARNING: linker noise\nOK|com.a.b\nFAIL|com.c.d|Error: x | y\n\nrandom|line"
        assertEquals(mapOf("com.a.b" to "", "com.c.d" to "Error: x | y"), PrivilegedPackageRemover.parseBatchOutput(out))
    }
}
