package com.diagramm.chart

import com.diagramm.model.Node
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SunburstTest {
    private fun tree(): Node = Node.directory(
        "/", "root",
        listOf(
            Node.directory("/a", "a", listOf(Node.file("/a/1", "1", 300), Node.file("/a/2", "2", 100))), // 400 = 40%
            Node.file("/b", "b", 400),                                                                    // 40%
            Node.file("/c", "c", 194),                                                                    // 19.4%
            Node.file("/tiny1", "t1", 3),                                                                 // .3% = 1.08 deg
            Node.file("/tiny2", "t2", 3),
        ),
    )

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-6

    @Test
    fun `angles are proportional to sizes`() {
        val s = SunburstLayout.layout(tree())
        val ring1 = s.arcsAt(1)
        assertEquals(listOf("a", "b", "c"), ring1.take(3).map { it.node!!.name })
        assertTrue(near(ring1[0].sweepAngle, 144.0))
        assertTrue(near(ring1[1].startAngle, 144.0))
        assertTrue(near(ring1[2].endAngle, 360.0 - 2.16))
    }

    @Test
    fun `tiny items merge into one smaller-objects arc`() {
        val s = SunburstLayout.layout(tree())
        val agg = s.arcsAt(1).last()
        assertTrue(agg.isAggregated)
        assertEquals(listOf("t1", "t2"), agg.aggregated.map { it.name })
        assertTrue(near(agg.sweepAngle, 2.16))
        assertTrue(near(agg.endAngle, 360.0))
    }

    @Test
    fun `children sit inside their parent's angular span`() {
        val s = SunburstLayout.layout(tree())
        val a = s.arcsAt(1).first()
        val kids = s.arcsAt(2)
        assertEquals(2, kids.size)
        assertTrue(kids.all { it.startAngle >= a.startAngle - 1e-9 && it.endAngle <= a.endAngle + 1e-9 })
        assertEquals(0, kids.first().colorIndex)
    }

    @Test
    fun `depth is limited`() {
        val deep = Node.directory("/", "r", listOf(
            Node.directory("/1", "1", listOf(Node.directory("/1/2", "2", listOf(Node.directory("/1/2/3", "3", listOf(Node.file("/1/2/3/f", "f", 10)))))))
        ))
        val s = SunburstLayout.layout(deep, SunburstConfig(maxDepth = 3))
        assertEquals(3, s.arcs.maxOf { it.depth })
    }

    @Test
    fun `hit test finds hub, arcs and nothing outside`() {
        val s = SunburstLayout.layout(tree())
        val g = SunburstGeometry(100.0, 100.0, 20.0, 15.0)
        assertIs<Hit.Center>(s.hit(g, 100.0, 100.0))
        // straight up from the centre, ring 1 => angle 0 => node "a"
        val up = s.hit(g, 100.0, 100.0 - 27.0)
        assertIs<Hit.OnArc>(up)
        assertEquals("a", up.arc.node!!.name)
        // straight right (90 deg) is inside "b" (144..288)? no: 90 is in "a" (0..144)
        val right = s.hit(g, 100.0 + 27.0, 100.0)
        assertEquals("a", (right as Hit.OnArc).arc.node!!.name)
        // straight down (180 deg) => "b"
        val down = s.hit(g, 100.0, 100.0 + 27.0)
        assertEquals("b", (down as Hit.OnArc).arc.node!!.name)
        // left (270 deg) => "c" starts at 288, so 270 is still "b"
        val left = s.hit(g, 100.0 - 27.0, 100.0)
        assertEquals("b", (left as Hit.OnArc).arc.node!!.name)
        // ring 2 straight up => child of "a"
        val ring2 = s.hit(g, 100.0, 100.0 - 42.0)
        assertEquals("1", (ring2 as Hit.OnArc).arc.node!!.name)
        // far outside the chart
        assertNull(s.hit(g, 100.0, 100.0 - 500.0))
    }

    @Test
    fun `free and hidden space do not consume colour slots`() {
        val root = Node.directory("/", "r", listOf(
            Node.freeSpace(500), Node.file("/a", "a", 300), Node.hiddenSpace(150), Node.file("/b", "b", 50),
        ))
        val s = SunburstLayout.layout(root)
        val byName = s.arcsAt(1).filter { it.node != null }.associateBy { it.node!!.name }
        assertEquals(-1, byName.getValue("Free space").colorIndex)
        assertEquals(-1, byName.getValue("Other data").colorIndex)
        assertEquals(0, byName.getValue("a").colorIndex)
        assertEquals(1, byName.getValue("b").colorIndex)
        assertEquals(2, s.branchCount)
    }

    @Test
    fun `empty folder has no arcs`() {
        val s = SunburstLayout.layout(Node.directory("/", "empty"))
        assertTrue(s.arcs.isEmpty())
        assertEquals(0, s.branchCount)
    }

    @Test
    fun `geometry fits the smaller side`() {
        val g = SunburstGeometry.fit(400.0, 300.0, 5)
        assertTrue(near(g.outerRadius(5), 150.0))
        assertTrue(near(g.centerX, 200.0))
    }
}
