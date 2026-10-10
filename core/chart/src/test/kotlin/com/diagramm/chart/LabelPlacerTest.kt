package com.diagramm.chart

import com.diagramm.model.Node
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelPlacerTest {
    private val g = SunburstGeometry(0.0, 0.0, centerRadius = 40.0, ringWidth = 50.0)
    private val lh = 14.0
    private val pad = 4.0
    private val placer = LabelPlacer(lineHeightPx = lh, minTextWidthPx = 30.0, paddingPx = pad)
    private val node = Node.file("/f", "f", 100)

    private fun arc(start: Double, sweep: Double, depth: Int = 1) = Arc(node, depth, start, sweep, 0)

    private fun place(arc: Arc, zoom: Double, nameW: Double = 60.0, sizeW: Double = 40.0) =
        placer.place(arc, g, zoom, { nameW }, { sizeW })

    @Test
    fun `readable never leaves text upside down`() {
        assertEquals(45.0, LabelPlacer.readable(45.0))
        assertEquals(20.0, LabelPlacer.readable(200.0))
        assertEquals(-45.0, LabelPlacer.readable(315.0))
        for (d in -720..720 step 7) {
            val r = LabelPlacer.readable(d.toDouble())
            assertTrue(r > -90.0 && r <= 90.0, "rotation $r for $d")
        }
    }

    @Test
    fun `a big segment gets name and size bent along the ring`() {
        val p = place(arc(0.0, 90.0), zoom = 1.0)
        assertNotNull(p)
        assertEquals(LabelOrientation.ALONG_RING, p.orientation)
        assertTrue(p.twoLines)
        assertEquals(45.0, p.angleDegrees)
        assertEquals(g.midRadius(1), p.radius)
        assertTrue(p.maxWidthPx in 40.0..60.0)
    }

    @Test
    fun `a thin segment shows nothing until zoomed in, then a short name`() {
        val thin = arc(0.0, 5.0)
        assertNull(place(thin, zoom = 1.0))
        val zoomed = place(thin, zoom = 8.0)
        assertNotNull(zoomed)
        assertTrue(!zoomed.twoLines)
    }

    @Test
    fun `a long ring segment on a thin ring gets one bent line`() {
        // the "free space" / "other data" case: huge sector, ring too thin for two lines
        val thinRing = SunburstGeometry(0.0, 0.0, centerRadius = 40.0, ringWidth = 24.0)
        val p = placer.place(arc(0.0, 250.0), thinRing, 1.0, { 200.0 }, { 40.0 })
        assertNotNull(p)
        assertEquals(LabelOrientation.ALONG_RING, p.orientation)
        assertTrue(!p.twoLines)
        assertTrue(p.maxWidthPx > 100.0) // plenty of arc to carry a long name
    }

    @Test
    fun `a label never extends past the end of its sector`() {
        for (depth in 1..4) for (sweep in listOf(8.0, 20.0, 45.0, 90.0, 180.0, 300.0, 360.0)) for (zoom in listOf(1.0, 2.0, 5.0, 10.0)) {
            val p = place(arc(0.0, sweep, depth), zoom, nameW = 500.0, sizeW = 25.0) ?: continue
            val rMid = g.midRadius(depth) * zoom
            val thickness = g.ringWidth * zoom
            val rInner = g.innerRadius(depth) * zoom
            when (p.orientation) {
                LabelOrientation.ALONG_RING -> {
                    val innermost = rMid - if (p.twoLines) lh / 2 else 0.0
                    // angular half-extent of the text on its innermost line stays inside the sector
                    val halfExtent = (p.maxWidthPx / 2) / innermost
                    assertTrue(halfExtent <= Math.toRadians(sweep) / 2 + 1e-9, "depth=$depth sweep=$sweep zoom=$zoom")
                    assertTrue((if (p.twoLines) 2 * lh else lh) + 2 * pad <= thickness + 1e-9)
                }
                LabelOrientation.ALONG_RADIUS -> {
                    assertTrue(p.maxWidthPx <= thickness - 2 * pad + 1e-9)
                    val room = 2 * (rInner + pad) * tan(minOf(Math.toRadians(sweep) / 2, 1.4))
                    assertTrue((if (p.twoLines) 2 * lh else lh) + 2 * pad <= room + 1e-9)
                }
            }
        }
    }

    @Test
    fun `zooming in can only add labels`() {
        val arcs = listOf(arc(0.0, 20.0), arc(0.0, 8.0, depth = 3), arc(10.0, 40.0, depth = 2), arc(0.0, 300.0))
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
    fun `aggregated segments can carry a label, empty ones cannot`() {
        assertNotNull(place(Arc(null, 1, 0.0, 120.0, -1, listOf(node)), 1.0))
        assertNull(place(arc(0.0, 0.0), 1.0))
    }

    @Test
    fun `size must fit in full but the name may be shortened`() {
        val a = arc(0.0, 60.0)
        val shortened = place(a, zoom = 1.0, nameW = 300.0, sizeW = 30.0)
        assertNotNull(shortened)
        assertTrue(shortened.twoLines)
        assertTrue(shortened.maxWidthPx < 300.0)
        // a size that cannot fit drops the second line instead of overflowing
        val oneLine = place(a, zoom = 1.0, nameW = 300.0, sizeW = 200.0)
        assertNotNull(oneLine)
        assertTrue(!oneLine.twoLines)
    }

    @Test
    fun `text widths are measured only when geometry allows`() {
        var measured = 0
        val p = placer.place(arc(0.0, 0.5), g, 1.0, { measured++; 10.0 }, { measured++; 10.0 })
        assertNull(p)
        assertEquals(0, measured)
    }

    @Test
    fun `ring length is capped and never negative`() {
        assertEquals(0.0, placer.ringLength(10.0, 1.0))
        assertTrue(placer.ringLength(100.0, 360.0) <= 100.0 * 2.1 + 1e-9)
        assertTrue(abs(placer.ringLength(100.0, 90.0) - (100.0 * Math.PI / 2 - 8.0)) < 1e-9)
    }
}
