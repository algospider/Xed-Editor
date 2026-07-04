package com.rk.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rk.icons.Icon
import com.rk.icons.XedIcon
import com.rk.resources.drawables
import com.rk.theme.DesignTokens

/**
 * Standardized bottom sheet for Xed-Editor.
 *
 * Provides consistent shape, colors, drag handle, and optional header
 * with title, subtitle, and close button.
 *
 * Usage:
 * ```kotlin
 * XedBottomSheet(onDismissRequest = { show = false }) {
 *     // scrollable content, lazy column, etc.
 * }
 *
 * XedBottomSheet(
 *     onDismissRequest = { show = false },
 *     title = { Text("Title") },
 *     subtitle = { Text("Description") },
 * ) {
 *     // content
 * }
 * ```
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
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = Dp.Unspecified,
        shape = DesignTokens.BottomSheet.shape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = DesignTokens.BottomSheet.scrimColor,
        tonalElevation = DesignTokens.BottomSheet.elevation,
        dragHandle = if (showDragHandle) {
            { XedDragHandle() }
        } else {
            null
        },
    ) {
        // ── Header section (title + subtitle + actions + close) ──
        val hasHeader = title != null || subtitle != null || headerContent != null || extraHeaderActions != null || showCloseButton
        if (hasHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = contentPadding,
                        end = if (showCloseButton) DesignTokens.Spacing.xsmall else contentPadding,
                        top = DesignTokens.Spacing.small,
                        bottom = DesignTokens.Spacing.small,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (headerContent != null) {
                    Box(modifier = Modifier.weight(1f)) { headerContent() }
                } else if (title != null) {
                    Box(modifier = Modifier.weight(1f)) {
                        title()
                        if (subtitle != null) {
                            Spacer(Modifier.height(DesignTokens.Spacing.xxsmall))
                            subtitle()
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                if (extraHeaderActions != null) {
                    extraHeaderActions()
                }

                if (showCloseButton) {
                    Spacer(Modifier.width(DesignTokens.Spacing.xsmall))
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp),
                    ) {
                        XedIcon(
                            icon = Icon.DrawableRes(drawables.close),
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                thickness = DesignTokens.Divider.thin,
            )
        }

        // ── Main content area ──
        // Note: padding is NOT applied here — callers should use
        // Modifier.padding(horizontal = DesignTokens.BottomSheet.contentPadding)
        // inside their content for consistency.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            content()
        }
    }
}
