package com.diagramm.data

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A small in-memory journal of what the privileged commands did (command line, exit code, output, errors).
 * It exists so that "it does not work" can be turned into facts: the user copies it from Settings and sends it.
 * Nothing is written to disk and nothing leaves the device unless the user shares it.
 */
object DiagnosticLog {
    private const val MAX_LINES = 400
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(message: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast("${LocalTime.now().format(clock)} $message")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    fun tail(count: Int): String = snapshot().takeLast(count).joinToString("\n")
}
