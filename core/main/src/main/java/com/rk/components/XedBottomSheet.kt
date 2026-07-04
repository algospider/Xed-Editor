package com.rk.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.rk.theme.DesignTokens

/**
 * Thin wrapper that delegates to [com.rk.components.compose.sheet.XedBottomSheet].
 *
 * Kept for backward compatibility with existing callers in this module.
 * New code should use [com.rk.components.compose.sheet.XedBottomSheet] directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XedBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    title: @Composable (() -> Unit)? = null,
    subtitle: @Composable (() -> Unit)? = null,
    showDragHandle: Boolean = true,
    showCloseButton: Boolean = true,
    headerContent: @Composable (() -> Unit)? = null,
    extraHeaderActions: @Composable (() -> Unit)? = null,
    contentPadding: Dp = DesignTokens.BottomSheet.contentPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    com.rk.components.compose.sheet.XedBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        title = title,
        subtitle = subtitle,
        showDragHandle = showDragHandle,
        showCloseButton = showCloseButton,
        headerContent = headerContent,
        extraHeaderActions = extraHeaderActions,
        contentPadding = contentPadding,
        content = content,
    )
}
