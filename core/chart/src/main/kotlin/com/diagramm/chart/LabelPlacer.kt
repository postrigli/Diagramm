package com.diagramm.chart

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where, and how, a label fits inside one ring segment.
 *
 * @property angleDegrees mid angle of the segment, clockwise from 12 o'clock
 * @property radius distance of the label centre from the chart centre, in *unzoomed* chart pixels
 * @property rotationDegrees text rotation (clockwise), already flipped so text is never upside down
 * @property maxWidthPx room along the text direction, in screen pixels; the name is ellipsized to it
 * @property twoLines name on the first line, size on the second; otherwise the name alone
 */
data class LabelPlacement(
    val angleDegrees: Double,
    val radius: Double,
    val rotationDegrees: Double,
    val maxWidthPx: Double,
    val twoLines: Boolean,
)

/**
 * Decides whether a segment is big enough (at the current zoom) to carry its name and size, and which
 * way the text should run: along the ring (tangential) or along the radius. All sizes are screen
 * pixels; the text widths are supplied lazily because measuring text is the expensive part.
 */
class LabelPlacer(
    private val lineHeightPx: Double,
    /** A name narrower than this is not worth showing, even ellipsized. */
    private val minTextWidthPx: Double,
    private val paddingPx: Double,
) {
    fun place(
        arc: Arc,
        g: SunburstGeometry,
        zoom: Double,
        nameWidth: () -> Double,
        sizeWidth: () -> Double,
    ): LabelPlacement? {
        if (arc.isAggregated || arc.sweepAngle <= 0.0) return null

        val mid = arc.startAngle + arc.sweepAngle / 2
        val rMid = g.midRadius(arc.depth) * zoom
        val rInner = g.innerRadius(arc.depth) * zoom
        val thickness = g.ringWidth * zoom - paddingPx
        val half = Math.toRadians(arc.sweepAngle) / 2
        fun chord(r: Double) = if (arc.sweepAngle >= 180.0) 2 * r else 2 * r * sin(half)

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

            // Along the ring: the text box sits at mid radius, its inner edge a bit closer to the centre.
            if (h + paddingPx <= thickness) {
                val w = chord(rMid - h / 2) - 2 * paddingPx
                fits(w, twoLines)?.let {
                    return LabelPlacement(mid, g.midRadius(arc.depth), readable(mid), it, twoLines)
                }
            }
            // Along the radius: text length is limited by the ring thickness, text height by the
            // arc's width at the inner edge.
            val wRadial = thickness - paddingPx
            if (h <= chord(rInner + paddingPx) - paddingPx) {
                fits(wRadial, twoLines)?.let {
                    return LabelPlacement(mid, g.midRadius(arc.depth), readable(mid - 90.0), it, twoLines)
                }
            }
        }
        return null
    }

    companion object {
        /** Normalises a rotation to (-90, 90] so text never reads upside down. */
        fun readable(degrees: Double): Double {
            var d = ((degrees % 360.0) + 360.0) % 360.0
            if (d > 90.0 && d <= 270.0) d -= 180.0 else if (d > 270.0) d -= 360.0
            return d
        }
    }
}
