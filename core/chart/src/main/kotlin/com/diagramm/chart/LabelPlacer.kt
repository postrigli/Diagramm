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
 * @property maxWidthPx room along the text direction, in screen pixels; the text is shortened to it
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
 * All sizes are screen pixels; text widths are supplied lazily because measuring text is the expensive part.
 */
class LabelPlacer(
    val lineHeightPx: Double,
    /** A name narrower than this is not worth showing, even shortened. */
    private val minTextWidthPx: Double,
    private val paddingPx: Double,
) {
    /** Usable text length along a circle of [radiusPx] inside a sector of [sweepDeg], minus padding at both ends. */
    fun ringLength(radiusPx: Double, sweepDeg: Double): Double =
        max(0.0, min(radiusPx * Math.toRadians(sweepDeg) - 2 * paddingPx, radiusPx * MAX_TEXT_ARC_RAD))

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

        fun fits(widthAvail: Double, twoLines: Boolean): Double? {
            if (widthAvail < minTextWidthPx) return null
            val name = nameWidth()
            if (!twoLines) return min(widthAvail, name)
            val size = sizeWidth()
            if (size > widthAvail) return null // the size must be shown in full
            return min(widthAvail, max(name, size))
        }

        for (twoLines in booleanArrayOf(true, false)) {
            val h = (if (twoLines) 2 else 1) * lineHeightPx

            // Along the ring: the lines sit around the mid radius; the innermost line is the shortest.
            if (h + 2 * paddingPx <= thickness) {
                val innermostLine = rMid - (if (twoLines) lineHeightPx / 2 else 0.0)
                fits(ringLength(innermostLine, arc.sweepAngle), twoLines)?.let {
                    return LabelPlacement(LabelOrientation.ALONG_RING, mid, g.midRadius(arc.depth), 0.0, it, twoLines)
                }
            }

            // Along the radius: length limited by the ring's thickness, height by the sector's width at the inner edge.
            val innerEdge = rInner + paddingPx
            val widthAtInnerEdge = 2 * innerEdge * tan(min(halfSweep, MAX_HALF_ANGLE_RAD))
            if (h + 2 * paddingPx <= widthAtInnerEdge) {
                fits(thickness - 2 * paddingPx, twoLines)?.let {
                    return LabelPlacement(
                        LabelOrientation.ALONG_RADIUS, mid, g.midRadius(arc.depth), readable(mid - 90.0), it, twoLines,
                    )
                }
            }
        }
        return null
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
