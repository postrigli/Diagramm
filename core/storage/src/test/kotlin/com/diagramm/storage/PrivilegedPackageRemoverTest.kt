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
}
