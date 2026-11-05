package dev.fajar.starter.designsystem.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.fajar.starter.designsystem.theme.LocalAppMotion
import kotlinx.coroutines.flow.collect

/**
 * Controlled finite tabs with native horizontal gestures. Only the settled page is active; callers
 * keep acquisition/effects outside inactive pages, including prefetched neighbours.
 */
@Composable
fun <T : Any> AppSwipeTabContent(
    items: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    stateKey: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (T, Boolean) -> Unit,
) {
    require(items.isNotEmpty() && items.size <= 8 && selected in items)
    val keys = items.map(stateKey)
    require(keys.distinct().size == keys.size)
    val index = items.indexOf(selected)
    val pager = rememberPagerState(initialPage = index, pageCount = { items.size })
    val latestItems by rememberUpdatedState(items)
    val latestSelected by rememberUpdatedState(index)
    val latestOnSelected by rememberUpdatedState(onSelected)
    val latestEnabled by rememberUpdatedState(enabled)
    val motion = LocalAppMotion.current
    var requested by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(index, keys, enabled, motion.tabMillis) {
        requested = index
        if (pager.currentPage != index || pager.currentPageOffsetFraction != 0f) {
            if (motion.tabMillis == 0 || pager.isScrollInProgress || !enabled) {
                // An explicit tab selection wins over the currently interrupted gesture.
                pager.requestScrollToPage(index)
            } else {
                pager.animateScrollToPage(index, animationSpec = tween(motion.tabMillis))
            }
        }
    }
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) requested = null
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { Triple(pager.settledPage, pager.isScrollInProgress, requested) }
            .collect { (page, scrolling, target) ->
                if (!scrolling) {
                    if (target != null) {
                        if (page == target) requested = null
                    } else if (latestEnabled && page != latestSelected) {
                        latestItems.getOrNull(page)?.let(latestOnSelected)
                    }
                }
            }
    }
    HorizontalPager(
        state = pager,
        modifier = modifier,
        key = { keys[it] },
        userScrollEnabled = enabled,
        beyondViewportPageCount = 1,
    ) { page ->
        val active = pager.settledPage == page
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (!active && !pager.isScrollInProgress) Modifier.clearAndSetSemantics {}
                    else Modifier
                )
        ) {
            content(items[page], active)
        }
    }
}
