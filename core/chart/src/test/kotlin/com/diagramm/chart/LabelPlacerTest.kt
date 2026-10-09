package com.diagramm.chart

import com.diagramm.model.Node
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelPlacerTest {
    private val g = SunburstGeometry(0.0, 0.0, centerRadius = 40.0, ringWidth = 50.0)
    private val placer = LabelPlacer(lineHeightPx = 14.0, minTextWidthPx = 30.0, paddingPx = 4.0)
    private val node = Node.file("/f", "f", 100)

    private fun arc(start: Double, sweep: Double, depth: Int = 1) = Arc(node, depth, start, sweep, 0)

    private fun place(arc: Arc, zoom: Double, nameW: Double = 60.0, sizeW: Double = 40.0) =
        placer.place(arc, g, zoom, { nameW }, { sizeW })

    @Test
    fun `readable never leaves text upside down`() {
        assertEquals(45.0, LabelPlacer.readable(45.0))
        assertEquals(20.0, LabelPlacer.readable(200.0))
        assertEquals(-45.0, LabelPlacer.readable(315.0))
        assertEquals(-87.5, LabelPlacer.readable(-87.5))
        for (d in -720..720 step 7) {
            val r = LabelPlacer.readable(d.toDouble())
            assertTrue(r > -90.0 && r <= 90.0, "rotation $r for $d")
        }
    }

    @Test
    fun `a big segment gets name and size along the ring`() {
        val p = place(arc(0.0, 90.0), zoom = 1.0)
        assertNotNull(p)
        assertTrue(p.twoLines)
        assertEquals(45.0, p.angleDegrees)
        assertEquals(45.0, p.rotationDegrees) // tangent at 45 degrees
        assertEquals(g.midRadius(1), p.radius)
        assertTrue(p.maxWidthPx <= 60.0 && p.maxWidthPx >= 40.0)
    }

    @Test
    fun `a thin segment shows nothing until zoomed in`() {
        val thin = arc(0.0, 5.0)
        assertNull(place(thin, zoom = 1.0))
        val zoomed = place(thin, zoom = 6.0)
        assertNotNull(zoomed)
        // too narrow for two lines: name only, running along the radius
        assertFalse(zoomed.twoLines)
        assertTrue(abs(zoomed.rotationDegrees - LabelPlacer.readable(2.5 - 90.0)) < 1e-9)
    }

    @Test
    fun `zooming in can only add labels`() {
        val arcs = listOf(arc(0.0, 20.0), arc(0.0, 8.0, depth = 3), arc(10.0, 40.0, depth = 2))
        for (a in arcs) {
            var shown = false
            for (zoom in listOf(1.0, 1.5, 2.0, 3.0, 5.0, 8.0)) {
                val p = place(a, zoom)
                if (shown) assertNotNull(p, "label disappeared when zooming in to $zoom")
                if (p != null) shown = true
            }
        }
    }

    @Test
    fun `aggregated and empty segments get no label`() {
        assertNull(place(Arc(null, 1, 0.0, 90.0, -1, listOf(node)), 1.0))
        assertNull(place(arc(0.0, 0.0), 1.0))
    }

    @Test
    fun `size must fit in full but the name may be shortened`() {
        // sweep 40 deg at depth 1: chord at box inner edge is ~ 2*65*sin(20deg) - 8 = ~36
        val a = arc(0.0, 40.0)
        val shortened = place(a, zoom = 1.0, nameW = 300.0, sizeW = 30.0)
        assertNotNull(shortened)
        assertTrue(shortened.maxWidthPx < 300.0)
        assertNull(place(a, zoom = 1.0, nameW = 300.0, sizeW = 200.0)?.takeIf { it.twoLines })
    }

    @Test
    fun `text widths are measured only when geometry allows`() {
        var measured = 0
        val p = placer.place(arc(0.0, 0.5), g, 1.0, { measured++; 10.0 }, { measured++; 10.0 })
        assertNull(p)
        assertEquals(0, measured)
    }
}
