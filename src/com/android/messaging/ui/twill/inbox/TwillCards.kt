package com.android.messaging.ui.twill.inbox

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Twill's inbox draws every conversation as its own card, grouped per section like the
 * Settings-style lists (see `SettingsLazyColumn`): 16dp from the sides, 3dp apart, 24dp
 * corners at the top and bottom of each section and 4dp corners between rows. Cards are
 * surface bright on the page colour (surface container).
 */
internal object TwillCards {
    val SideMargin = 16.dp
    val RowGap = 3.dp
    private val OuterCorner = 24.dp
    private val InnerCorner = 4.dp

    /** The card shape of row [index] of [count] rows in one section. */
    fun shape(index: Int, count: Int): Shape {
        val top = if (index == 0) OuterCorner else InnerCorner
        val bottom = if (index == count - 1) OuterCorner else InnerCorner
        return RoundedCornerShape(
            topStart = top,
            topEnd = top,
            bottomStart = bottom,
            bottomEnd = bottom,
        )
    }
}

/**
 * The card shape of the conversation row being drawn, provided by the inbox so upstream's
 * row and swipe background use it; null keeps upstream's pill-shaped rows.
 */
internal val LocalTwillCardShape = compositionLocalOf<Shape?> { null }
