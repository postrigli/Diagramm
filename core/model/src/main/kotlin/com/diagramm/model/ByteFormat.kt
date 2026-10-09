package com.diagramm.model

import java.util.Locale

/** Decimal (SI) size formatting like DaisyDisk: "166.4 GB", "34 GB", "697.3 MB". */
object ByteFormat {
    val DEFAULT_UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

    fun format(bytes: Long, units: List<String> = DEFAULT_UNITS, locale: Locale = Locale.ROOT): String {
        val negative = bytes < 0
        var value = kotlin.math.abs(bytes.toDouble())
        var unit = 0
        while (value >= 1000.0 && unit < units.lastIndex) {
            value /= 1000.0
            unit++
        }
        val sign = if (negative) "-" else ""
        if (unit == 0) return "$sign${value.toLong()} ${units[0]}"
        var rounded = Math.round(value * 10.0) / 10.0
        if (rounded >= 1000.0 && unit < units.lastIndex) {
            // 999.96 MB rounds up to 1000 -> show as 1 GB
            rounded = Math.round(rounded / 1000.0 * 10.0) / 10.0
            unit++
        }
        val text = if (rounded == Math.floor(rounded)) rounded.toLong().toString() else String.format(locale, "%.1f", rounded)
        return "$sign$text ${units[unit]}"
    }

    /** Value and unit index, for UIs that render number and unit separately (e.g. the collector badge). */
    fun split(bytes: Long, units: List<String> = DEFAULT_UNITS, locale: Locale = Locale.ROOT): Pair<String, String> {
        val s = format(bytes, units, locale)
        val i = s.lastIndexOf(' ')
        return s.substring(0, i) to s.substring(i + 1)
    }
}
