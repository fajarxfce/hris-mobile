package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*

/** Follow a new bottom-row key during layout while preserving an older reading position. */
@Composable
internal fun ChatMessageEndViewport(
    newestMessageKey: String?,
    enabled: Boolean,
    list: LazyListState,
) {
    var displayed by remember(list) { mutableStateOf(newestMessageKey) }
    SideEffect {
        if (displayed != newestMessageKey) {
            displayed = newestMessageKey
            if (
                enabled &&
                    !list.isScrollInProgress &&
                    list.firstVisibleItemIndex == 0 &&
                    list.firstVisibleItemScrollOffset == 0
            ) {
                list.requestScrollToItem(0)
            }
        }
    }
}
