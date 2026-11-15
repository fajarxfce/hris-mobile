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
import dev.fajar.starter.collaboration.presentation.recipient.RecipientChatThreadEvent
import dev.fajar.starter.collaboration.presentation.recipient.RecipientChatThreadViewModel
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
@Composable
fun PrepareVisibleSpaceChatThreads(
    owner: SpaceChatNavigationOwner,
    conversationIds: List<String>,
    list: LazyListState,
    active: Boolean = true,
) {
    PrepareVisibleSpaceChatDestinations(
        owner,
        conversationIds.map { SpaceChatTarget.Conversation(it) },
        list,
        active,
    )
}

/** Contacts use the same idle budget and prepare only the already-owned local recipient facts. */
@Composable
fun PrepareVisibleSpaceChatRecipients(
    owner: SpaceChatNavigationOwner,
    peerAccountIds: List<String>,
    list: LazyListState,
    active: Boolean = true,
) {
    PrepareVisibleSpaceChatDestinations(
        owner,
        peerAccountIds.map { SpaceChatTarget.Recipient(it) },
        list,
        active,
    )
}

@OptIn(KoinInternalApi::class)
@Composable
private fun PrepareVisibleSpaceChatDestinations(
    owner: SpaceChatNavigationOwner,
    targets: List<SpaceChatTarget>,
    list: LazyListState,
    active: Boolean,
) {
    val scope = currentKoinScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available = targets.associateBy { it.id }
    LaunchedEffect(owner, available, active, list, lifecycle, scope) {
        if (owner.closed) return@LaunchedEffect
        if (!active) {
            owner.prepareTargets(emptyList())
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
                                    .mapNotNull { available[it] }
                                    .take(3)
                        }
                        .filterNotNull()
                        .distinctUntilChanged()
                        .collect { visible ->
                            ensureActive()
                            owner.prepareTargets(visible).forEach { (target, stored) ->
                                when (target) {
                                    is SpaceChatTarget.Conversation -> {
                                        val model =
                                            resolveViewModel(
                                                SpaceChatThreadViewModel::class,
                                                stored.models,
                                                key =
                                                    spaceChatThreadModelKey(
                                                        owner.access,
                                                        target.id,
                                                    ),
                                                extras = CreationExtras.Empty,
                                                scope = scope,
                                                parameters = {
                                                    parametersOf(owner.access, target.id)
                                                },
                                            )
                                        stored.bind(model)
                                        model.onEvent(SpaceChatThreadEvent.Prepared)
                                    }
                                    is SpaceChatTarget.Recipient -> {
                                        val model =
                                            resolveViewModel(
                                                RecipientChatThreadViewModel::class,
                                                stored.models,
                                                key =
                                                    spaceChatRecipientModelKey(
                                                        owner.access,
                                                        target.id,
                                                    ),
                                                extras = CreationExtras.Empty,
                                                scope = scope,
                                                parameters = {
                                                    parametersOf(owner.access, target.id)
                                                },
                                            )
                                        stored.bind(model)
                                        model.onEvent(RecipientChatThreadEvent.Prepared)
                                    }
                                }
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
