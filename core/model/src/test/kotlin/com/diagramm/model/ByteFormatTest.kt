package com.diagramm.model

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ByteFormatTest {
    @Test
    fun `matches DaisyDisk style`() {
        assertEquals("0 B", ByteFormat.format(0))
        assertEquals("999 B", ByteFormat.format(999))
        assertEquals("1 KB", ByteFormat.format(1000))
        assertEquals("166.4 GB", ByteFormat.format(166_400_000_000))
        assertEquals("34 GB", ByteFormat.format(34_000_000_000))
        assertEquals("697.3 MB", ByteFormat.format(697_300_000))
        assertEquals("4 GB", ByteFormat.format(4_000_000_000))
    }

    @Test
    fun `rounding up to the next unit`() {
        assertEquals("1 GB", ByteFormat.format(999_960_000))
    }

    @Test
    fun `uses locale decimal separator and custom units`() {
        val ru = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        assertEquals("1,5 ГБ", ByteFormat.format(1_500_000_000, ru, Locale.forLanguageTag("ru")))
    }

    @Test
    fun `split gives number and unit`() {
        assertEquals("10.9" to "GB", ByteFormat.split(10_900_000_000))
    }
}
