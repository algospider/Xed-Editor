package com.rk.tabs.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rk.components.XedDragHandle
import com.rk.icons.XedIcon
import com.rk.resources.drawables
import com.rk.theme.DesignTokens
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Stable
class ToolSheetState(
    val density: Density,
    val coroutineScope: CoroutineScope,
    minHeightDp: Dp,
    maxHeightDp: Dp,
    initialHeightDp: Dp,
) {
    var minHeightPx by mutableFloatStateOf(minHeightDp.value * density.density)
        private set
    var maxHeightPx by mutableFloatStateOf(maxHeightDp.value * density.density)
        private set

    var heightPx by mutableFloatStateOf(initialHeightDp.value * density.density)
        private set

    val heightDp: Dp
        get() = (heightPx / density.density).dp

    fun updateBounds(minPx: Float, maxPx: Float) {
        minHeightPx = minPx
        maxHeightPx = maxPx
        heightPx = heightPx.coerceIn(minPx, maxPx)
    }

    fun snapTo(px: Float) {
        heightPx = px.coerceIn(minHeightPx, maxHeightPx)
    }
}

@Composable
fun rememberToolSheetState(
    minHeight: Dp,
    initialHeight: Dp,
    maxHeight: Dp,
): ToolSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val state = remember {
        ToolSheetState(
            density = density,
            coroutineScope = coroutineScope,
            minHeightDp = minHeight,
            maxHeightDp = maxHeight,
            initialHeightDp = initialHeight,
        )
    }

    LaunchedEffect(minHeight, maxHeight) {
        val minPx = minHeight.value * density.density
        val maxPx = maxHeight.value * density.density
        state.updateBounds(minPx, maxPx)
    }

    return state
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ToolSheetContainer(
    onDismissRequest: () -> Unit,
    cwd: String,
    session: TerminalSession?,
    modifier: Modifier = Modifier,
    showTerminal: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
    controls: (@Composable RowScope.() -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val imeVisible = WindowInsets.isImeVisible
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .background(colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            // Header: Stable 38dp height, pinned under status bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    headerContent?.invoke()
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    controls?.invoke(this)

                    Spacer(Modifier.width(4.dp))

                    FilledIconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(28.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            contentColor = colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        XedIcon(
                            com.rk.icons.Icon.DrawableRes(drawables.close),
                            contentDescription = "Close",
                            modifier = Modifier.size(13.dp),
                            tint = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(
                color = colorScheme.outlineVariant.copy(alpha = 0.15f),
                thickness = 0.5.dp,
            )

            // Middle Content: Terminal or Panel Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(colorScheme.surfaceContainerLow),
            ) {
                if (showTerminal) {
                    SheetTerminal(
                        session = session,
                        modifier = Modifier.fillMaxSize(),
                        showKeys = true,
                    )
                } else {
                    content?.invoke()
                }
            }

            // Bottom Bar: Command Bar / Extra Keys
            bottomBar?.let {
                HorizontalDivider(
                    color = colorScheme.outlineVariant.copy(alpha = 0.12f),
                    thickness = 0.5.dp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colorScheme.surfaceContainer)
                        .then(
                            if (!imeVisible) Modifier.navigationBarsPadding()
                            else Modifier
                        ),
                ) {
                    it()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolSheetModalContainer(
    onDismissRequest: () -> Unit,
    cwd: String,
    session: TerminalSession?,
    modifier: Modifier = Modifier,
    showTerminal: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
    controls: (@Composable RowScope.() -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    ToolSheetContainer(
        onDismissRequest = onDismissRequest,
        cwd = cwd,
        session = session,
        modifier = modifier,
        showTerminal = showTerminal,
        headerContent = headerContent,
        controls = controls,
        bottomBar = bottomBar,
        content = content,
    )
}
