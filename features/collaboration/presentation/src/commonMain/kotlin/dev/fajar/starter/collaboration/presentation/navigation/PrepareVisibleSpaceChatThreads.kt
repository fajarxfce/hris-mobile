package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadEvent
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.currentKoinScope
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.parameter.parametersOf
import org.koin.viewmodel.resolveViewModel

/** The Group navigation owner prepares at most three visible local destinations while idle. */
@OptIn(KoinInternalApi::class)
@Composable
fun PrepareVisibleSpaceChatThreads(
    owner: SpaceChatNavigationOwner,
    conversationIds: List<String>,
    list: LazyListState,
    active: Boolean = true,
) {
    val scope = currentKoinScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available = conversationIds.toSet()
    LaunchedEffect(owner, available, active, list, lifecycle, scope) {
        if (owner.closed) return@LaunchedEffect
        if (!active) {
            owner.prepare(emptyList())
            return@LaunchedEffect
        }
        // Either graph retirement or composition disposal cancels this reader. The graph may
        // close before an outgoing navigation composition has left the screen.
        val preparation =
            owner.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    snapshotFlow {
                            if (list.isScrollInProgress) null
                            else
                                list.layoutInfo.visibleItemsInfo
                                    .mapNotNull { it.key as? String }
                                    .filter { it in available }
                                    .take(3)
                        }
                        .filterNotNull()
                        .distinctUntilChanged()
                        .collect { visible ->
                            ensureActive()
                            owner.prepare(visible).forEach { (id, stored) ->
                                val model =
                                    resolveViewModel(
                                        SpaceChatThreadViewModel::class,
                                        stored.models,
                                        key = spaceChatThreadModelKey(owner.access, id),
                                        extras = CreationExtras.Empty,
                                        scope = scope,
                                        parameters = { parametersOf(owner.access, id) },
                                    )
                                stored.bind(model)
                                model.onEvent(SpaceChatThreadEvent.Prepared)
                            }
                        }
                }
            }
        try {
            preparation.join()
        } finally {
            withContext(NonCancellable) { preparation.cancelAndJoin() }
        }
    }
}
