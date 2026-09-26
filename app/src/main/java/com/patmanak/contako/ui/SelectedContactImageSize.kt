package com.patmanak.contako.ui

import kotlin.math.roundToInt

/**
 * Proton's contact picker targets a 180 px short edge (see CONTACTS.md).
 * Contako additionally avoids enlargement and bounds extreme aspect ratios.
 */
internal fun selectedContactImageSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf(
        1.0,
        SELECTED_IMAGE_SHORT_EDGE.toDouble() / minOf(width, height),
        SELECTED_IMAGE_LONG_EDGE.toDouble() / maxOf(width, height),
    )
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

private const val SELECTED_IMAGE_SHORT_EDGE = 180
private const val SELECTED_IMAGE_LONG_EDGE = 1_024
