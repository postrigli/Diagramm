package com.diagramm.chart

import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

enum class LabelOrientation {
    /** Text bent along the ring (the sector's mid radius), like DaisyDisk. */
    ALONG_RING,

    /** Straight text running along the radius: for narrow sectors across a thick ring. */
    ALONG_RADIUS,
}

/**
 * Where, and how, a label fits inside one ring segment.
 *
 * @property angleDegrees mid angle of the segment, clockwise from 12 o'clock
 * @property radius distance of the label centre from the chart centre, in *unzoomed* chart pixels
 * @property rotationDegrees [LabelOrientation.ALONG_RADIUS] only: text rotation, already flipped so text is never upside down
 * @property maxWidthPx room along the text direction, in screen pixels; text longer than this is shortened
 * @property twoLines name on the first line, size on the second; otherwise a single line
 */
data class LabelPlacement(
    val orientation: LabelOrientation,
    val angleDegrees: Double,
    val radius: Double,
    val rotationDegrees: Double,
    val maxWidthPx: Double,
    val twoLines: Boolean,
)

/**
 * Decides whether a segment is big enough (at the current zoom) to carry its name and size, and which
 * way the text runs. The limits are exact geometry, so a label never leaves its sector: along the ring
 * the text is bent to the arc and limited by the arc's length at the *innermost* text line; along the
 * radius it is limited by the ring's thickness and by the sector's width at the inner edge.
 *
 * All four ways of showing the label (along the ring or the radius, one or two lines) are compared and
 * the one that shows the most of the name wins; when the whole name fits in several, two lines and
 * ring-wise text are preferred. So a tall, narrow sector gets vertical text instead of a short, cut-off
 * horizontal one.
 *
 * All sizes are screen pixels; text widths are supplied lazily because measuring text is the expensive part.
 */
class LabelPlacer(
    val lineHeightPx: Double,
    /** A name narrower than this is not worth showing, even shortened. */
    private val minTextWidthPx: Double,
    private val paddingPx: Double,
    /** Share of the geometric room text may use; keeps long names a little clear of the sector edges. */
    private val fill: Double = 0.94,
) {
    /** Usable text length along a circle of [radiusPx] inside a sector of [sweepDeg], minus padding at both ends. */
    fun ringLength(radiusPx: Double, sweepDeg: Double): Double =
        max(0.0, min(radiusPx * Math.toRadians(sweepDeg) - 2 * paddingPx, radiusPx * MAX_TEXT_ARC_RAD))

    private class Candidate(val placement: LabelPlacement, val shown: Double, val wholeName: Boolean)

    fun place(
        arc: Arc,
        g: SunburstGeometry,
        zoom: Double,
        nameWidth: () -> Double,
        sizeWidth: () -> Double,
    ): LabelPlacement? {
        if (arc.sweepAngle <= 0.0) return null

        val mid = arc.startAngle + arc.sweepAngle / 2
        val rMid = g.midRadius(arc.depth) * zoom
        val rInner = g.innerRadius(arc.depth) * zoom
        val rOuter = g.outerRadius(arc.depth) * zoom
        val thickness = rOuter - rInner
        val halfSweep = Math.toRadians(arc.sweepAngle) / 2

        var name = -1.0
        var size = -1.0
        fun nameW(): Double { if (name < 0) name = nameWidth(); return name }
        fun sizeW(): Double { if (size < 0) size = sizeWidth(); return size }

        val candidates = ArrayList<Candidate>(4)
        fun consider(orientation: LabelOrientation, rotation: Double, room: Double, twoLines: Boolean) {
            if (room < minTextWidthPx) return
            if (twoLines && sizeW() > room) return // the size must be shown in full
            val n = nameW()
            val shown = min(room, if (twoLines) max(n, sizeW()) else n)
            candidates.add(
                Candidate(
                    LabelPlacement(orientation, mid, g.midRadius(arc.depth), rotation, room, twoLines),
                    shown, n <= room,
                ),
            )
        }

        for (twoLines in booleanArrayOf(true, false)) {
            val h = (if (twoLines) 2 else 1) * lineHeightPx

            // Along the ring: the lines sit around the mid radius; the innermost line is the shortest.
            if (h + 2 * paddingPx <= thickness) {
                val innermostLine = rMid - (if (twoLines) lineHeightPx / 2 else 0.0)
                consider(LabelOrientation.ALONG_RING, 0.0, ringLength(innermostLine, arc.sweepAngle) * fill, twoLines)
            }

            // Along the radius: length limited by the ring's thickness, height by the sector's width at the inner edge.
            val innerEdge = rInner + paddingPx
            val widthAtInnerEdge = 2 * innerEdge * tan(min(halfSweep, MAX_HALF_ANGLE_RAD))
            if (h + 2 * paddingPx <= widthAtInnerEdge) {
                consider(LabelOrientation.ALONG_RADIUS, readable(mid - 90.0), (thickness - 2 * paddingPx) * fill, twoLines)
            }
        }
        if (candidates.isEmpty()) return null

        // The whole name wins over a cut one; then two lines over one, ring-wise over radial.
        // Without a whole-name fit, whichever shows most (two lines and ring-wise text get a small bonus).
        fun score(c: Candidate): Double {
            val p = c.placement
            val bonus = (if (p.twoLines) 1.25 else 1.0) * (if (p.orientation == LabelOrientation.ALONG_RING) 1.1 else 1.0)
            return (if (c.wholeName) 1e9 else 0.0) + (if (c.wholeName) bonus else c.shown * bonus)
        }
        return candidates.maxBy { score(it) }.placement
    }

    companion object {
        /** Text longer than ~120 degrees of arc would curl around the chart; names are never that long. */
        private const val MAX_TEXT_ARC_RAD = 2.1
        private const val MAX_HALF_ANGLE_RAD = 1.4

        /** Normalises a rotation to (-90, 90] so text never reads upside down. */
        fun readable(degrees: Double): Double {
            var d = ((degrees % 360.0) + 360.0) % 360.0
            if (d > 90.0 && d <= 270.0) d -= 180.0 else if (d > 270.0) d -= 360.0
            return d
        }
    }
}
