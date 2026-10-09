package com.diagramm.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.diagramm.chart.Arc
import com.diagramm.chart.CategoricalPalette
import com.diagramm.model.NodeKind

/**
 * Sector colours. Each top-level branch takes the next hue of a fixed, colour-blind-safe sequence
 * (blue, orange, aqua, yellow, magenta, green, violet, red - see [CategoricalPalette]); everything
 * inside a branch keeps its hue and only shifts slightly in lightness with depth, so the nested
 * rings read as "part of that folder". Branches beyond the eighth reuse the hues in a paler variant.
 * Free space, hidden data and aggregated small objects are neutral greys, never a hue.
 */
class ChartPalette(private val dark: Boolean) {
    private val free = if (dark) Color(0xFF3A404D) else Color(0xFFD3D8E2)
    private val hidden = if (dark) Color(0xFF6A7283) else Color(0xFF9AA2B3)
    private val small = if (dark) Color(0xFF8A91A1) else Color(0xFFB7BDCB)

    fun branch(index: Int, depth: Int): Color {
        val base = Color(0xFF000000L or CategoricalPalette.slot(index, dark).toLong())
        val tier = CategoricalPalette.tier(index).coerceAtMost(2)
        val paler = if (tier == 0) base else lerp(base, Color.White, 0.32f * tier)
        val step = 0.07f * (depth - 1)
        // lighter outwards on dark surfaces, slightly darker outwards on light ones: contrast with the
        // background never drops below the validated base colour
        return if (dark) lerp(paler, Color.White, step) else lerp(paler, Color.Black, step * 0.8f)
    }

    fun arc(arc: Arc): Color {
        val node = arc.node ?: return small
        return when (node.kind) {
            NodeKind.FREE_SPACE -> free
            NodeKind.HIDDEN_SPACE -> hidden
            else -> if (arc.colorIndex < 0) small else branch(arc.colorIndex, arc.depth)
        }
    }

    val smallObjects: Color get() = small
}

/** Dark text on light sectors, white text on dark ones. */
fun textColorOn(background: Color): Color =
    if (background.luminance() > 0.45f) Color(0xFF10131A) else Color.White
