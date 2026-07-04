package com.rk.components.compose.sheet

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
    contentPadding: Dp = SheetTokens.contentPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = Dp.Unspecified,
        shape = SheetTokens.shape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = SheetTokens.scrimColor,
        tonalElevation = SheetTokens.elevation,
        dragHandle = if (showDragHandle) {
            { SheetDragHandle() }
        } else {
            null
        },
    ) {
        val columnScope = this

        val hasHeader = title != null || subtitle != null || headerContent != null || extraHeaderActions != null || showCloseButton
        if (hasHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = contentPadding,
                        end = if (showCloseButton) SheetTokens.Spacing.xsmall else contentPadding,
                        top = SheetTokens.Spacing.small,
                        bottom = SheetTokens.Spacing.small,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (headerContent != null) {
                    Box(modifier = Modifier.weight(1f)) { headerContent() }
                } else if (title != null) {
                    Box(modifier = Modifier.weight(1f)) {
                        title()
                        if (subtitle != null) {
                            Spacer(Modifier.height(SheetTokens.Spacing.xxsmall))
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
                    Spacer(Modifier.width(SheetTokens.Spacing.xsmall))
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                thickness = SheetTokens.Divider.thin,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            with(columnScope) { content() }
        }
    }
}
