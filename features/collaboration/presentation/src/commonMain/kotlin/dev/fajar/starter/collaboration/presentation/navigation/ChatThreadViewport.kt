package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadEvent
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadState
import kotlinx.coroutines.flow.*

/** Owns route-local end anchoring and visibility; domain admission still guards read writes. */
@Composable
internal fun ChatThreadViewport(
    state: ChatThreadState,
    list: LazyListState,
    onEvent: (ChatThreadEvent) -> Unit,
) {
    val newestKey = state.rows.firstOrNull()?.key
    var displayedNewestKey by remember(list) { mutableStateOf(newestKey) }
    SideEffect {
        if (displayedNewestKey != newestKey) {
            displayedNewestKey = newestKey
            // Preserve the end position in this layout pass. Stable-key anchoring would
            // otherwise keep the previous last message visible and place the new one below it
            // until the later send-confirmation effect requests a scroll.
            if (
                state.forwarding == null &&
                    !state.leaving &&
                    state.localFailure == null &&
                    !state.browsingHistory &&
                    !state.hasNewer &&
                    !list.isScrollInProgress &&
                    list.firstVisibleItemIndex == 0 &&
                    list.firstVisibleItemScrollOffset == 0
            )
                list.requestScrollToItem(0)
        }
    }
    val current by rememberUpdatedState(state)
    val dispatch by rememberUpdatedState(onEvent)
    val owner = LocalLifecycleOwner.current
    val window = LocalWindowInfo.current
    LaunchedEffect(owner, window, list) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                snapshotFlow {
                        val layout = list.layoutInfo
                        val height = layout.viewportEndOffset - layout.viewportStartOffset
                        val active =
                            window.isWindowFocused &&
                                !current.leaving &&
                                current.localFailure == null &&
                                current.forwarding == null &&
                                current.draftDialog == null &&
                                current.cancellationDialog == null &&
                                height > 0
                        val keys =
                            if (!active) emptyList()
                            else
                                layout.visibleItemsInfo
                                    .filter {
                                        val visible =
                                            minOf(it.offset + it.size, layout.viewportEndOffset) -
                                                maxOf(it.offset, layout.viewportStartOffset)
                                        // At least half of the message (or viewport for a very tall
                                        // message).
                                        it.size > 0 &&
                                            visible > 0 &&
                                            visible * 2 >= minOf(it.size, height)
                                    }
                                    .mapNotNull { it.key as? String }
                        ChatThreadEvent.ViewportChanged(current.readViewportToken, keys)
                    }
                    .distinctUntilChanged()
                    .collect(dispatch)
            } finally {
                dispatch(ChatThreadEvent.ViewportChanged(current.readViewportToken, emptyList()))
            }
        }
    }
}
