package com.diagramm.chart

import com.diagramm.model.Node
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * One ring segment. Angles are in degrees, measured clockwise from 12 o'clock.
 * [node] is null for the aggregated "smaller objects" segment, whose members are in [aggregated].
 */
data class Arc(
    val node: Node?,
    /** Ring number, 1 = the ring right around the centre. */
    val depth: Int,
    val startAngle: Double,
    val sweepAngle: Double,
    /** Index of the depth-1 branch this arc descends from; -1 for aggregated segments. Drives colouring. */
    val colorIndex: Int,
    val aggregated: List<Node> = emptyList(),
) {
    val endAngle: Double get() = startAngle + sweepAngle
    val isAggregated: Boolean get() = node == null
    val aggregatedSize: Long get() = aggregated.sumOf { it.size }
}

data class SunburstConfig(
    val maxDepth: Int = 5,
    /** Segments narrower than this many degrees are merged into one "smaller objects" segment. */
    val minSweepDegrees: Double = 1.5,
)

data class SunburstGeometry(
    val centerX: Double,
    val centerY: Double,
    /** Radius of the empty hub in the middle. */
    val centerRadius: Double,
    val ringWidth: Double,
) {
    fun innerRadius(depth: Int): Double = centerRadius + (depth - 1) * ringWidth
    fun outerRadius(depth: Int): Double = centerRadius + depth * ringWidth
    fun midRadius(depth: Int): Double = (innerRadius(depth) + outerRadius(depth)) / 2

    companion object {
        /** Fits the chart into a [width] x [height] box. */
        fun fit(width: Double, height: Double, maxDepth: Int, hubFraction: Double = 0.24): SunburstGeometry {
            val half = minOf(width, height) / 2
            val hub = half * hubFraction
            return SunburstGeometry(width / 2, height / 2, hub, (half - hub) / maxDepth)
        }
    }
}

sealed interface Hit {
    data object Center : Hit
    data class OnArc(val arc: Arc) : Hit
}

class Sunburst internal constructor(
    val root: Node,
    val config: SunburstConfig,
    val arcs: List<Arc>,
) {
    private val byDepth: List<List<Arc>> = (1..config.maxDepth).map { d ->
        arcs.filter { it.depth == d }.sortedBy { it.startAngle }
    }

    fun arcsAt(depth: Int): List<Arc> = byDepth.getOrElse(depth - 1) { emptyList() }

    /** Number of distinct depth-1 branches, for spreading colours. */
    val branchCount: Int = arcs.count { it.depth == 1 && !it.isAggregated }

    fun hit(geometry: SunburstGeometry, x: Double, y: Double): Hit? {
        val dx = x - geometry.centerX
        val dy = y - geometry.centerY
        val r = hypot(dx, dy)
        if (r < geometry.centerRadius) return Hit.Center
        val depth = ((r - geometry.centerRadius) / geometry.ringWidth).toInt() + 1
        if (depth > config.maxDepth) return null
        var angle = Math.toDegrees(atan2(dx, -dy))
        if (angle < 0) angle += 360.0
        val ring = arcsAt(depth)
        // binary search: last arc starting at or before the angle
        var lo = 0
        var hi = ring.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (ring[mid].startAngle <= angle) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        val arc = ring[found]
        return if (angle < arc.endAngle) Hit.OnArc(arc) else null
    }
}

object SunburstLayout {
    fun layout(root: Node, config: SunburstConfig = SunburstConfig()): Sunburst {
        val arcs = ArrayList<Arc>()
        place(root, 0.0, 360.0, 1, -1, config, arcs)
        return Sunburst(root, config, arcs)
    }

    private fun place(
        parent: Node,
        parentStart: Double,
        parentSweep: Double,
        depth: Int,
        parentColor: Int,
        config: SunburstConfig,
        out: MutableList<Arc>,
    ) {
        if (depth > config.maxDepth || parent.size <= 0 || parentSweep < config.minSweepDegrees) return
        var cursor = parentStart
        val aggregated = ArrayList<Node>()
        var aggregatedSize = 0L
        var branch = 0
        for (child in parent.children) {
            if (child.size <= 0) continue
            val sweep = parentSweep * child.size / parent.size
            if (sweep < config.minSweepDegrees) {
                aggregated.add(child)
                aggregatedSize += child.size
                continue
            }
            val color = if (depth == 1) branch++ else parentColor
            out.add(Arc(child, depth, cursor, sweep, color))
            place(child, cursor, sweep, depth + 1, color, config, out)
            cursor += sweep
        }
        if (aggregated.isNotEmpty()) {
            out.add(Arc(null, depth, cursor, parentSweep * aggregatedSize / parent.size, -1, aggregated))
        }
    }
}
