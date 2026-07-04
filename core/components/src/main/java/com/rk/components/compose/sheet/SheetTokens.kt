package com.rk.components.compose.sheet

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design constants for bottom sheet components.
 * Self-contained copy of relevant values from [com.rk.theme.DesignTokens.BottomSheet].
 */
object SheetTokens {
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    val scrimColor = Color.Black.copy(alpha = 0.45f)
    val elevation = 4.dp
    val contentPadding = 16.dp
    val dragHandleWidth = 32.dp
    val dragHandleHeight = 4.dp
    val dragHandleHitAreaHeight = 20.dp
    val dragHandleCornerRadius = 2.dp
    val minSheetHeight = 260.dp
    val headerMinHeight = 48.dp

    object Spacing {
        val none = 0.dp
        val xxsmall = 2.dp
        val xsmall = 4.dp
        val small = 8.dp
        val medium = 12.dp
        val large = 16.dp
        val xlarge = 24.dp
    }

    object Divider {
        val thin = 0.5.dp
        val regular = 1.dp
    }
}
