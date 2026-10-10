package com.diagramm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diagramm.chart.Arc
import com.diagramm.chart.Hit
import com.diagramm.chart.LabelOrientation
import com.diagramm.chart.LabelPlacer
import com.diagramm.chart.Sunburst
import com.diagramm.chart.SunburstGeometry
import com.diagramm.model.Collector
import com.diagramm.model.Node
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val MAX_ZOOM = 12f

/**
 * The DaisyDisk-style ring chart. Layout (angles, grouping of small items, label fitting) comes from
 * the pure `:core:chart` module; this composable paints it, handles pinch-zoom / pan / tap and draws
 * the name and size inside every sector that is big enough at the current zoom.
 *
 * @param labelText text shown for a node in its sector (localised for free/hidden space)
 * @param aggregatedText text shown in the grey sector that merges many small objects
 */
@Composable
fun SunburstChart(
    sunburst: Sunburst?,
    palette: ChartPalette,
    collector: Collector,
    focusId: String?,
    centerTitle: String,
    centerSubtitle: String,
    labelText: (Node) -> String,
    aggregatedText: (List<Node>) -> String,
    sizeText: (Long) -> String,
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

    // Zoom (1 = whole chart fits) and pan, both in screen pixels around the chart centre.
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // A different folder is a different chart: start from the overview again.
    LaunchedEffect(sunburst?.root) {
        zoom = 1f
        pan = Offset.Zero
    }

    val currentOnArc by rememberUpdatedState(onArc)
    val currentOnCenter by rememberUpdatedState(onCenter)
    val currentLabelText by rememberUpdatedState(labelText)
    val currentAggregatedText by rememberUpdatedState(aggregatedText)
    val currentSizeText by rememberUpdatedState(sizeText)
    val focusColor = MaterialTheme.colorScheme.onBackground
    val collectColor = MaterialTheme.colorScheme.error

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)
    val nameStyle = remember { TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
    val sizeStyle = remember { TextStyle(fontSize = 10.sp) }
    val placer = remember(density) {
        with(density) { LabelPlacer(lineHeightPx = 14.sp.toPx().toDouble(), minTextWidthPx = 34.dp.toPx().toDouble(), paddingPx = 3.dp.toPx().toDouble()) }
    }

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
                        detectTapGestures { tap ->
                            val sb = sunburst ?: return@detectTapGestures
                            val g = SunburstGeometry.fit(size.width.toDouble(), size.height.toDouble(), sb.config.maxDepth)
                            val c = Offset(size.width / 2f, size.height / 2f)
                            // undo pan and zoom to get back into chart coordinates
                            val p = c + (tap - c - pan) / zoom
                            when (val hit = sb.hit(g, p.x.toDouble(), p.y.toDouble())) {
                                Hit.Center -> currentOnCenter()
                                is Hit.OnArc -> currentOnArc(hit.arc)
                                null -> Unit
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, panChange, zoomChange, _ ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val half = min(size.width, size.height) / 2f
                            val oldZoom = zoom
                            val newZoom = (oldZoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                            if (newZoom <= 1.001f) {
                                zoom = 1f
                                pan = Offset.Zero
                            } else {
                                // keep the point under the fingers where it is while zooming
                                val rel = centroid - c
                                val moved = rel - (rel - pan) * (newZoom / oldZoom) + panChange
                                val limit = (newZoom - 1f) * half + half * 0.15f
                                zoom = newZoom
                                pan = Offset(moved.x.coerceIn(-limit, limit), moved.y.coerceIn(-limit, limit))
                            }
                        }
                    },
            ) {
                if (sunburst != null) {
                    val t = appear.value
                    val g = SunburstGeometry.fit(size.width.toDouble(), size.height.toDouble(), sunburst.config.maxDepth)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    withTransform({
                        translate(pan.x, pan.y)
                        scale(zoom, zoom, pivot = center)
                    }) {
                        drawSunburst(sunburst, g, palette, collectedIds, focusId, t, zoom, focusColor, collectColor)
                    }
                    if (t > 0.6f) {
                        drawLabels(
                            sunburst, g, palette, zoom, pan, center, textMeasurer, placer,
                            nameStyle, sizeStyle, currentLabelText, currentAggregatedText, currentSizeText, t,
                        )
                    }
                }
            }
            // The hub is a circle of HUB_FRACTION of the chart radius; text must stay inside its inscribed box.
            val hubTextWidth = side * (SunburstGeometry.HUB_FRACTION.toFloat() * 0.80f)
            Column(
                Modifier
                    .align(Alignment.Center)
                    .width(hubTextWidth)
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FitText(
                    centerTitle, hubTextWidth, textMeasurer,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    minSize = 10.sp, maxLines = 1,
                    color = MaterialTheme.colorScheme.primary,
                )
                FitText(
                    centerSubtitle, hubTextWidth, textMeasurer,
                    style = MaterialTheme.typography.bodySmall,
                    minSize = 8.sp, maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (zoom > 1.02f) {
                Text(
                    text = "%.1f×".format(zoom),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            zoom = 1f
                            pan = Offset.Zero
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawSunburst(
    sb: Sunburst,
    g: SunburstGeometry,
    palette: ChartPalette,
    collectedIds: Set<String>,
    focusId: String?,
    t: Float,
    zoom: Float,
    focusColor: Color,
    collectColor: Color,
) {
    // Strokes live inside the zoomed canvas, so divide by the zoom to keep gaps and outlines a
    // constant width on screen.
    val gapPx = 1.5.dp.toPx() / zoom
    val stroke = (g.ringWidth.toFloat() - gapPx).coerceAtLeast(1f / zoom)
    val outline = 3.dp.toPx() / zoom
    val pivot = Offset(g.centerX.toFloat(), g.centerY.toFloat())

    scale(0.9f + 0.1f * t, pivot) {
        for (arc in sb.arcs) {
            drawRing(arc, g, palette.arc(arc).copy(alpha = t), stroke, gapPx, 0f)
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

/**
 * Names and sizes inside the sectors. Drawn in screen space (after the zoom transform) so the text
 * keeps a fixed, readable size; [LabelPlacer] decides per sector whether and how it fits.
 */
private fun DrawScope.drawLabels(
    sb: Sunburst,
    g: SunburstGeometry,
    palette: ChartPalette,
    zoom: Float,
    pan: Offset,
    center: Offset,
    measurer: TextMeasurer,
    placer: LabelPlacer,
    nameStyle: TextStyle,
    sizeStyle: TextStyle,
    labelText: (Node) -> String,
    aggregatedText: (List<Node>) -> String,
    sizeText: (Long) -> String,
    alpha: Float,
) {
    val margin = 40.dp.toPx()
    val origin = center + pan
    val lineHeight = placer.lineHeightPx.toFloat()
    for (arc in sb.arcs) {
        // Cheap cull first: skip sectors whose centre is far outside the visible area.
        val mid = Math.toRadians(arc.startAngle + arc.sweepAngle / 2)
        val rMid = g.midRadius(arc.depth)
        val sx = origin.x + (zoom * rMid * sin(mid)).toFloat()
        val sy = origin.y - (zoom * rMid * cos(mid)).toFloat()
        if (sx < -margin || sx > size.width + margin || sy < -margin || sy > size.height + margin) continue

        val node = arc.node
        val name = if (node != null) labelText(node) else aggregatedText(arc.aggregated)
        val sizeLabel = sizeText(node?.size ?: arc.aggregatedSize)
        val placement = placer.place(
            arc, g, zoom.toDouble(),
            nameWidth = { measurer.measure(name, nameStyle).size.width.toDouble() },
            sizeWidth = { measurer.measure(sizeLabel, sizeStyle).size.width.toDouble() },
        ) ?: continue

        val textColor = textColorOn(palette.arc(arc)).copy(alpha = alpha)
        val sizeColor = textColor.copy(alpha = 0.85f * alpha)
        // Leave a little air on both ends so even the widest glyphs stay clear of the sector's edges.
        val maxWidth = (placement.maxWidthPx * TEXT_FILL).toFloat()

        when (placement.orientation) {
            LabelOrientation.ALONG_RADIUS -> {
                val constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1))
                val nameLayout = measurer.measure(
                    name, nameStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = constraints,
                )
                val sizeLayout = if (placement.twoLines) {
                    measurer.measure(sizeLabel, sizeStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = constraints)
                } else {
                    null
                }
                val blockHeight = nameLayout.size.height + (sizeLayout?.size?.height ?: 0)
                val topY = sy - blockHeight / 2f
                rotate(placement.rotationDegrees.toFloat(), pivot = Offset(sx, sy)) {
                    drawText(nameLayout, color = textColor, topLeft = Offset(sx - nameLayout.size.width / 2f, topY))
                    if (sizeLayout != null) {
                        drawText(
                            sizeLayout,
                            color = sizeColor,
                            topLeft = Offset(sx - sizeLayout.size.width / 2f, topY + nameLayout.size.height),
                        )
                    }
                }
            }

            LabelOrientation.ALONG_RING -> {
                // Lower half of the chart: text runs counter-clockwise with its top toward the centre, so it
                // never reads upside down; the first line is then the inner one.
                val lower = cos(mid) < 0
                val r = zoom * placement.radius.toFloat()
                val nameRadius: Float
                val sizeRadius: Float
                if (placement.twoLines) {
                    nameRadius = if (lower) r - lineHeight / 2 else r + lineHeight / 2
                    sizeRadius = if (lower) r + lineHeight / 2 else r - lineHeight / 2
                } else {
                    nameRadius = r
                    sizeRadius = r
                }
                drawBentText(measurer, name, nameStyle, origin, nameRadius, placement.angleDegrees, lower, maxWidth, textColor, margin)
                if (placement.twoLines) {
                    drawBentText(measurer, sizeLabel, sizeStyle, origin, sizeRadius, placement.angleDegrees, lower, maxWidth, sizeColor, margin)
                }
            }
        }
    }
}

/** Shortest prefix-with-ellipsis of [text] whose glyph advances fit [maxWidth]; also returns those advances. */
private fun fitGlyphs(measurer: TextMeasurer, text: String, style: TextStyle, maxWidth: Float): Pair<String, List<Float>> {
    fun advances(t: String) = t.map { measurer.measure(it.toString(), style).size.width.toFloat() }
    val full = advances(text)
    if (full.sum() <= maxWidth) return text to full
    val dots = measurer.measure("…", style).size.width.toFloat()
    var used = dots
    var count = 0
    while (count < text.length && used + full[count] <= maxWidth) {
        used += full[count]
        count++
    }
    val shown = text.take(count).trimEnd()
    if (shown.isEmpty()) return "" to emptyList()
    val result = "$shown…"
    return result to advances(result)
}

/**
 * Draws [text] bent along the circle of [radius] around [origin], centred on [angleDegrees] (clockwise from
 * 12 o'clock). With [lower] the text runs counter-clockwise and is turned over so it stays readable.
 */
private fun DrawScope.drawBentText(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    origin: Offset,
    radius: Float,
    angleDegrees: Double,
    lower: Boolean,
    maxWidth: Float,
    color: Color,
    cullMargin: Float,
) {
    if (radius <= 0f) return
    val (shown, advances) = fitGlyphs(measurer, text, style, maxWidth)
    if (shown.isEmpty()) return
    val total = advances.sum()
    val direction = if (lower) -1.0 else 1.0
    var offset = -total / 2f
    for (i in shown.indices) {
        val advance = advances[i]
        val centreOffset = offset + advance / 2f
        offset += advance
        if (shown[i].isWhitespace()) continue
        val theta = angleDegrees + direction * Math.toDegrees((centreOffset / radius).toDouble())
        val rad = Math.toRadians(theta)
        val px = origin.x + (radius * sin(rad)).toFloat()
        val py = origin.y - (radius * cos(rad)).toFloat()
        if (px < -cullMargin || px > size.width + cullMargin || py < -cullMargin || py > size.height + cullMargin) continue
        val glyph = measurer.measure(shown[i].toString(), style)
        rotate((if (lower) theta + 180.0 else theta).toFloat(), pivot = Offset(px, py)) {
            drawText(
                glyph,
                color = color,
                topLeft = Offset(px - glyph.size.width / 2f, py - glyph.size.height / 2f),
            )
        }
    }
}

/** Single-line / two-line text that shrinks its font until it fits [maxWidth], never going below [minSize]. */
@Composable
private fun FitText(
    text: String,
    maxWidth: Dp,
    measurer: TextMeasurer,
    style: TextStyle,
    minSize: TextUnit,
    maxLines: Int,
    color: Color,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.roundToPx() }
    val fitted = remember(text, widthPx, style, minSize, maxLines) {
        var size = style.fontSize.value
        val floor = minSize.value
        var current = style
        while (true) {
            current = style.copy(fontSize = size.sp)
            val layout = measurer.measure(
                text, current, maxLines = maxLines, softWrap = maxLines > 1,
                constraints = Constraints(maxWidth = widthPx),
            )
            if ((!layout.didOverflowWidth && !layout.didOverflowHeight) || size <= floor) break
            size = (size - 0.5f).coerceAtLeast(floor)
        }
        current
    }
    Text(
        text, style = fitted, color = color, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
    )
}

/** Share of the exactly-fitting text length that is actually used: a little more aggressive shortening. */
private const val TEXT_FILL = 0.92
