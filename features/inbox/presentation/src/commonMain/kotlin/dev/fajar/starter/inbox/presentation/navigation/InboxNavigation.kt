package dev.fajar.starter.inbox.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
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
    composable<InboxRoute> { InboxContent(workspaceId, onItem, onVerify, onBack) }
    composable<InboxItemRoute> { entry ->
        val target = InboxItemTarget(workspaceId, entry.toRoute<InboxItemRoute>().id)
        val viewModel = koinViewModel<InboxItemViewModel>(parameters = { parametersOf(target) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(InboxItemEvent.Entered)
            onStopOrDispose { viewModel.onEvent(InboxItemEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                InboxItemEffect.Back -> onBack()
                InboxItemEffect.VerifyAccount -> onVerify()
            }
        }
        InboxItemPage(state, viewModel::onEvent)
    }
}

/** The Home tab and standalone route use separate ViewModel owners. */
@Composable
fun InboxContent(
    workspaceId: String,
    onItem: (String) -> Unit,
    onVerify: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val viewModel = koinViewModel<InboxViewModel>(parameters = { parametersOf(workspaceId) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LifecycleStartEffect(viewModel) {
        viewModel.onEvent(InboxEvent.Entered)
        onStopOrDispose { viewModel.onEvent(InboxEvent.Stopped) }
    }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            InboxEffect.Back -> onBack?.invoke()
            InboxEffect.ScrollToStart -> list.requestScrollToItem(0)
            InboxEffect.VerifyAccount -> onVerify()
            is InboxEffect.OpenItem -> onItem(effect.id)
        }
    }
    InboxPage(state, list, viewModel::onEvent, showBack = onBack != null)
}
