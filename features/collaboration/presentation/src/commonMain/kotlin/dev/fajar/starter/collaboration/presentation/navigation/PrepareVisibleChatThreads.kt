package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import dev.fajar.starter.collaboration.presentation.inbox.ChatInboxState
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadEvent
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import org.koin.compose.currentKoinScope
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.parameter.parametersOf
import org.koin.viewmodel.resolveViewModel

/** Navigation prepares bounded screen state; domain readers still authorize every publication. */
@OptIn(KoinInternalApi::class)
@Composable
internal fun PrepareVisibleChatThreads(
    workspaceId: String,
    state: ChatInboxState,
    list: LazyListState,
    stores: ChatThreadViewModelStores,
) {
    val scope = currentKoinScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = state.owner?.takeUnless { state.restricted }
    val conversations = state.page?.items.orEmpty().map { it.conversation.id }.toSet()
    LaunchedEffect(stores, workspaceId, owner, conversations, lifecycle, scope) {
        if (owner == null) {
            stores.prepare(emptyList())
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow {
                    if (list.isScrollInProgress) null
                    else
                        list.layoutInfo.visibleItemsInfo
                            .mapNotNull { it.key as? String }
                            .filter { it in conversations }
                            .take(3)
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { visible ->
                    stores.prepare(visible).forEach { (id, stored) ->
                        val model =
                            resolveViewModel(
                                ChatThreadViewModel::class,
                                stored.models,
                                extras = CreationExtras.Empty,
                                scope = scope,
                                parameters = { parametersOf(workspaceId, id) },
                            )
                        stored.bind(model)
                        model.onEvent(ChatThreadEvent.LocalPrepared(owner))
                    }
                }
        }
    }
}
