package dev.fajar.starter.inbox.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.inbox.presentation.detail.*
import dev.fajar.starter.inbox.presentation.detail.pages.InboxItemPage
import dev.fajar.starter.inbox.presentation.list.*
import dev.fajar.starter.inbox.presentation.list.pages.InboxPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.inboxRoutes(
    workspaceId: String,
    onItem: (String) -> Unit,
    onVerify: () -> Unit,
    onBack: () -> Unit,
) {
    composable<InboxRoute> {
        val viewModel = koinViewModel<InboxViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onEvent(InboxEvent.Entered) }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onEvent(InboxEvent.Stopped) }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                InboxEffect.Back -> onBack()
                InboxEffect.ScrollToStart -> list.requestScrollToItem(0)
                InboxEffect.VerifyAccount -> onVerify()
                is InboxEffect.OpenItem -> onItem(effect.id)
            }
        }
        InboxPage(state, list, viewModel::onEvent)
    }
    composable<InboxItemRoute> { entry ->
        val target = InboxItemTarget(workspaceId, entry.toRoute<InboxItemRoute>().id)
        val viewModel = koinViewModel<InboxItemViewModel>(parameters = { parametersOf(target) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onEvent(InboxItemEvent.Entered) }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onEvent(InboxItemEvent.Stopped) }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                InboxItemEffect.Back -> onBack()
                InboxItemEffect.VerifyAccount -> onVerify()
            }
        }
        InboxItemPage(state, viewModel::onEvent)
    }
}
