package com.diagramm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diagramm.chart.Arc
import com.diagramm.chart.Hit
import com.diagramm.chart.Sunburst
import com.diagramm.chart.SunburstGeometry
import com.diagramm.model.Collector

/**
 * The DaisyDisk-style ring chart. Layout (angles, grouping of small items) comes from the pure
 * `:core:chart` module; this composable only paints it and turns taps into [Arc]s.
 */
@Composable
fun SunburstChart(
    sunburst: Sunburst?,
    palette: ChartPalette,
    collector: Collector,
    focusId: String?,
    centerTitle: String,
    centerSubtitle: String,
    onArc: (Arc) -> Unit,
    onCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appear = remember { Animatable(1f) }
    LaunchedEffect(sunburst) {
        if (sunburst != null) {
            appear.snapTo(0f)
            appear.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        }
    }
    val currentOnArc by rememberUpdatedState(onArc)
    val currentOnCenter by rememberUpdatedState(onCenter)
    val focusColor = MaterialTheme.colorScheme.onBackground
    val collectColor = MaterialTheme.colorScheme.error

    val collectedIds = remember(sunburst, collector) {
        if (sunburst == null || collector.isEmpty) {
            emptySet()
        } else {
            sunburst.arcs.mapNotNull { a -> a.node?.takeIf { !it.isSynthetic && collector.covers(it) }?.id }.toSet()
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(Modifier.size(side)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(sunburst) {
                        detectTapGestures { offset ->
                            val sb = sunburst ?: return@detectTapGestures
                            val g = SunburstGeometry.fit(size.width.toDouble(), size.height.toDouble(), sb.config.maxDepth)
                            when (val hit = sb.hit(g, offset.x.toDouble(), offset.y.toDouble())) {
                                Hit.Center -> currentOnCenter()
                                is Hit.OnArc -> currentOnArc(hit.arc)
                                null -> Unit
                            }
                        }
                    },
            ) {
                if (sunburst != null) {
                    drawSunburst(sunburst, palette, collectedIds, focusId, appear.value, focusColor, collectColor)
                }
            }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .width(side * 0.44f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    centerTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
                Text(
                    centerSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun DrawScope.drawSunburst(
    sb: Sunburst,
    palette: ChartPalette,
    collectedIds: Set<String>,
    focusId: String?,
    t: Float,
    focusColor: Color,
    collectColor: Color,
) {
    val g = SunburstGeometry.fit(size.width.toDouble(), size.height.toDouble(), sb.config.maxDepth)
    val gapPx = 1.5.dp.toPx()
    val stroke = (g.ringWidth.toFloat() - gapPx).coerceAtLeast(1f)
    val outline = 3.dp.toPx()
    val pivot = Offset(g.centerX.toFloat(), g.centerY.toFloat())

    scale(0.9f + 0.1f * t, pivot) {
        for (arc in sb.arcs) {
            drawRing(arc, g, palette.arc(arc, sb.branchCount).copy(alpha = t), stroke, gapPx, 0f)
            val node = arc.node ?: continue
            if (node.id == focusId) {
                drawRing(arc, g, focusColor.copy(alpha = 0.95f * t), outline, gapPx, stroke / 2 + outline / 2)
            }
            if (node.id in collectedIds) {
                drawRing(arc, g, collectColor.copy(alpha = t), outline, gapPx, -(stroke / 2 - outline / 2))
            }
        }
    }
}

private fun DrawScope.drawRing(
    arc: Arc,
    g: SunburstGeometry,
    color: Color,
    strokeWidth: Float,
    gapPx: Float,
    radiusOffset: Float,
) {
    val r = g.midRadius(arc.depth).toFloat() + radiusOffset
    val gapDeg = Math.toDegrees((gapPx / r).toDouble())
    val sweep = (arc.sweepAngle - gapDeg).coerceAtLeast(0.3)
    val start = arc.startAngle + gapDeg / 2 - 90.0 // Compose measures from 3 o'clock, our angles from 12
    drawArc(
        color = color,
        startAngle = start.toFloat(),
        sweepAngle = sweep.toFloat(),
        useCenter = false,
        topLeft = Offset(g.centerX.toFloat() - r, g.centerY.toFloat() - r),
        size = Size(2 * r, 2 * r),
        style = Stroke(width = strokeWidth),
    )
}
