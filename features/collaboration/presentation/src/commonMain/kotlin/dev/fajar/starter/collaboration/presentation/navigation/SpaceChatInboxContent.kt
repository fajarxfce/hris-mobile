package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import dev.fajar.starter.collaboration.presentation.spaceinbox.*
import dev.fajar.starter.collaboration.presentation.spaceinbox.pages.SpaceChatInboxPage
import kotlin.time.Clock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The caller supplies an account/Group store and the existing warm thread navigation owner. */
@Composable
fun SpaceChatInboxContent(
    graph: SpaceChatNavigationOwner,
    viewModelOwner: ViewModelStoreOwner,
    onConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    bottomPadding: Dp = 8.dp,
) {
    if (graph.closed) return
    key(graph.access, viewModelOwner) {
        val model =
            koinViewModel<SpaceChatInboxViewModel>(
                viewModelStoreOwner = viewModelOwner,
                key = "space-inbox:" + spaceChatThreadModelKey(graph.access, "inbox"),
                parameters = { parametersOf(graph.access) },
            )
        val state by model.state.collectAsStateWithLifecycle()
        val entries = model.entries.collectAsLazyPagingItems()
        val list = rememberLazyListState()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val onOpen by rememberUpdatedState(onConversation)
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(zone).date
        val loaded = entries.itemSnapshotList.items.map { it.conversationId }
        PrepareVisibleSpaceChatThreads(graph, loaded, list, active && state.restriction == null)
        if (active) {
            LifecycleStartEffect(model) {
                model.onEvent(SpaceChatInboxEvent.Entered)
                onStopOrDispose { model.onEvent(SpaceChatInboxEvent.Stopped) }
            }
            LaunchedEffect(model, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    model.effects.collect { effect ->
                        when (effect) {
                            is SpaceChatInboxEffect.OpenConversation ->
                                if (!graph.closed) onOpen(effect.id)
                        }
                    }
                }
            }
            LaunchedEffect(model, lifecycle, entries, list) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    snapshotFlow {
                            val ids = entries.itemSnapshotList.items.map { it.conversationId }
                            val visible =
                                list.layoutInfo.visibleItemsInfo
                                    .mapNotNull { it.key as? String }
                                    .filter { it in ids }
                                    .take(50)
                            visible to ids.take(200)
                        }
                        .distinctUntilChanged()
                        .collect { (visible, ids) ->
                            model.onEvent(SpaceChatInboxEvent.ViewportChanged(visible, ids))
                        }
                }
            }
        }
        SpaceChatInboxPage(
            state,
            entries,
            list,
            today,
            zone,
            model::onEvent,
            modifier,
            bottomPadding,
        )
    }
}
