package com.diagramm.ui

import androidx.compose.ui.graphics.Color
import com.diagramm.chart.Arc
import com.diagramm.model.NodeKind
import kotlin.math.abs
import kotlin.math.max

/** Hue per top-level branch, lightness fading with depth - the DaisyDisk look. */
class ChartPalette(private val dark: Boolean) {
    private val freeColor = if (dark) Color(0xFF2E3C60) else Color(0xFFDDE3F2)
    private val hiddenColor = if (dark) Color(0xFF5B6B94) else Color(0xFF98A4C2)
    private val smallColor = if (dark) Color(0xFF7B88AB) else Color(0xFFB5BED6)

    fun branch(index: Int, count: Int, depth: Int): Color {
        val hue = (150f + 300f * index / max(1, count)) % 360f
        val lightness = if (dark) max(0.30f, 0.66f - 0.06f * (depth - 1)) else max(0.36f, 0.58f - 0.05f * (depth - 1))
        return hsl(hue, if (dark) 0.55f else 0.62f, lightness)
    }

    fun arc(arc: Arc, branchCount: Int): Color {
        val node = arc.node ?: return smallColor
        return when (node.kind) {
            NodeKind.FREE_SPACE -> freeColor
            NodeKind.HIDDEN_SPACE -> hiddenColor
            else -> branch(arc.colorIndex, branchCount, arc.depth)
        }
    }

    val smallObjects: Color get() = smallColor
    val free: Color get() = freeColor
    val hidden: Color get() = hiddenColor
}

/** HSL -> RGB; hue in degrees [0, 360), saturation and lightness in [0, 1]. */
private fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = h / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r, g, b) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(r + m, g + m, b + m)
}
