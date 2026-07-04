package com.rk.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Thin wrapper that delegates to [com.rk.components.compose.sheet.SheetDragHandle].
 *
 * Kept for backward compatibility with existing callers.
 * New code should use [com.rk.components.compose.sheet.SheetDragHandle] directly.
 */
@Composable
fun XedDragHandle(
    isDragging: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    com.rk.components.compose.sheet.SheetDragHandle(
        isDragging = isDragging,
        modifier = modifier,
        onClick = onClick,
    )
}
