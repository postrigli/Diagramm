package com.diagramm.chart

/**
 * Colours for the depth-1 branches of the chart, in a fixed order: the biggest branch gets slot 0,
 * the next slot 1, and so on, so neighbouring sectors always differ strongly.
 *
 * The eight hues (blue, orange, aqua, yellow, magenta, green, violet, red) and their order were
 * validated for colour-vision deficiency: worst neighbouring pair is ΔE 9.1 (light) / 8.4 (dark)
 * under protanopia/deuteranopia simulation and ΔE 19+ for normal vision (OKLab x100; targets are
 * >= 8 and >= 15). Do not reorder or retune individual entries without re-validating them.
 *
 * Values are 0xRRGGBB. The dark set is the same hues stepped for dark surfaces.
 */
object CategoricalPalette {
    val light: List<Int> = listOf(0x2A78D6, 0xEB6834, 0x1BAF7A, 0xEDA100, 0xE87BA4, 0x008300, 0x4A3AA7, 0xE34948)
    val dark: List<Int> = listOf(0x3987E5, 0xD95926, 0x199E70, 0xC98500, 0xD55181, 0x008300, 0x9085E9, 0xE66767)

    val size: Int get() = light.size

    fun slot(index: Int, dark: Boolean): Int = (if (dark) this.dark else light)[index % size]

    /** 0 for the first eight branches, 1 for the next eight (drawn as a paler variant), and so on. */
    fun tier(index: Int): Int = index / size
}
