package dev.fajar.starter.inbox.presentation.badge

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.inbox.domain.entities.InboxCacheQuery
import dev.fajar.starter.inbox.domain.usecases.LoadUnreadInbox
import dev.fajar.starter.inbox.domain.usecases.ObserveInbox
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class InboxBadgeViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveInbox,
    private val load: LoadUnreadInbox,
) : MviViewModel<InboxBadgeState, InboxBadgeEvent, Nothing>(InboxBadgeState()) {
    private var observation: Job? = null

    init {
        on<InboxBadgeEvent.Entered>(::onEntered)
        on<InboxBadgeEvent.Stopped>(::onStopped)
    }

    private fun onEntered(event: InboxBadgeEvent.Entered) {
        if (observation?.isActive == true) return
        observation =
            viewModelScope.launch {
                observe(workspaceId, InboxCacheQuery.Checkpoint).collectLatest { checkpoint ->
                    if (checkpoint is AppResult.Failed) {
                        updateState { InboxBadgeState() }
                        return@collectLatest
                    }
                    // Sync may commit several pages together; only its latest progress needs a
                    // read.
                    delay(150)
                    val result = load(workspaceId)
                    ensureActive()
                    updateState { InboxBadgeState((result as? AppResult.Success)?.value) }
                }
            }
    }

    private fun onStopped(event: InboxBadgeEvent.Stopped) {
        observation?.cancel()
        observation = null
        updateState { InboxBadgeState() }
    }
}
