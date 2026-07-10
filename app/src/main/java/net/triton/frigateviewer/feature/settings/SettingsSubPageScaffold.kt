package net.triton.frigateviewer.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Shared chrome for every Settings sub-page: collapsing large title + fixed back button +
 * scrollable body. Ported from PixelPlayer's `CollapsibleCommonTopBar`/`ExpressiveTopBarContent`
 * (github.com/PixelPlayerHQ/PixelPlayer) — same mechanics (nested-scroll-driven Animatable
 * height, title padding/scale/vertical-bias lerp so it slides up to sit beside the fixed
 * circular back button as it collapses, background fades in once collapse starts), minus the
 * variable-font width-axis polish that library uses (no matching font asset here).
 */
@Composable
fun SettingsSubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 160.dp + statusBarHeight
    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }

    val topBarHeight = remember(maxTopBarHeightPx) { Animatable(maxTopBarHeightPx) }
    var collapseFraction by remember { mutableStateOf(0f) }

    LaunchedEffect(topBarHeight.value, maxTopBarHeightPx) {
        collapseFraction =
            1f -
            ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx))
                .coerceIn(0f, 1f)
    }

    val nestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    val delta = available.y
                    val isScrollingDown = delta < 0
                    if (!isScrollingDown && scrollState.value > 0) return Offset.Zero

                    val previousHeight = topBarHeight.value
                    val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                    val consumed = newHeight - previousHeight
                    if (consumed.roundToInt() != 0) {
                        scope.launch { topBarHeight.snapTo(newHeight) }
                    }
                    val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                    return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
                }
            }
        }

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand = scrollState.value == 0
            val target = if (shouldExpand && canExpand) maxTopBarHeightPx else minTopBarHeightPx
            if (topBarHeight.value != target) {
                scope.launch { topBarHeight.animateTo(target, spring(stiffness = Spring.StiffnessMedium)) }
            }
        }
    }

    Box(Modifier.nestedScroll(nestedScrollConnection).fillMaxSize()) {
        val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    PaddingValues(
                        top = currentTopBarHeightDp + 8.dp,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )

        // Header: fades in a solid background once collapse starts, title slides/scales up to
        // sit beside the fixed circular back button as the header shrinks.
        val solidAlpha = (collapseFraction * 2f).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(currentTopBarHeightDp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = solidAlpha))
                .zIndex(5f),
        ) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                val titlePaddingStart = lerp(20.dp, 68.dp, collapseFraction)
                val titleVerticalBias = lerp(1f, -1f, collapseFraction)
                val titleScale = lerp(1.15f, 0.9f, collapseFraction)
                val titleContainerHeight = lerp(88.dp, 56.dp, collapseFraction)

                Box(
                    Modifier
                        .align(BiasAlignment(horizontalBias = -1f, verticalBias = titleVerticalBias))
                        .height(titleContainerHeight)
                        .fillMaxWidth()
                        .padding(start = titlePaddingStart, end = 24.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .align(Alignment.CenterStart)
                                .graphicsLayer {
                                    scaleX = titleScale
                                    scaleY = titleScale
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                },
                    )
                }

                FilledIconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 4.dp).zIndex(1f),
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        }
    }
}
