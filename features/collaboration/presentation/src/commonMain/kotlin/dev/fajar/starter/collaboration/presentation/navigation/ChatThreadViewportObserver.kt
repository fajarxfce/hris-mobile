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

/** Rendering evidence only; the ViewModel admits exact retained originals before local writes. */
@Composable
internal fun ChatThreadViewportObserver(
    state: ChatThreadState,
    list: LazyListState,
    onEvent: (ChatThreadEvent) -> Unit,
) {
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
