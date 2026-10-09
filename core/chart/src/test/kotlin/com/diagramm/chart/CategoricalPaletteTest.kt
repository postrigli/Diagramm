package com.diagramm.chart

import kotlin.test.Test
import kotlin.test.assertEquals

class CategoricalPaletteTest {
    @Test
    fun `eight distinct colours per mode in the validated order`() {
        assertEquals(8, CategoricalPalette.light.toSet().size)
        assertEquals(8, CategoricalPalette.dark.toSet().size)
        // first slots: blue, orange, aqua, yellow - the order is the colour-blind-safety mechanism
        assertEquals(listOf(0x2A78D6, 0xEB6834, 0x1BAF7A, 0xEDA100), CategoricalPalette.light.take(4))
    }

    @Test
    fun `slots wrap into paler tiers after eight`() {
        assertEquals(CategoricalPalette.light[0], CategoricalPalette.slot(8, dark = false))
        assertEquals(0, CategoricalPalette.tier(7))
        assertEquals(1, CategoricalPalette.tier(8))
    }
}
