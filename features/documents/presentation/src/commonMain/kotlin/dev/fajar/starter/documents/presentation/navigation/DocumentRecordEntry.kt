package dev.fajar.starter.documents.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.documents.presentation.record.*
import dev.fajar.starter.documents.presentation.record.pages.DocumentRecordPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

internal fun NavGraphBuilder.documentRecordRoute(
    workspaceId: String,
    onVerify: () -> Unit,
    onBack: () -> Unit,
) {
    composable<DocumentRecordRoute> { entry ->
        val route = entry.toRoute<DocumentRecordRoute>()
        val viewModel =
            koinViewModel<DocumentRecordViewModel>(
                parameters = { parametersOf(DocumentRecordTarget(workspaceId, route.documentId)) }
            )
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        DisposableEffect(viewModel) {
            viewModel.onEvent(DocumentRecordEvent.Opened)
            onDispose { viewModel.onEvent(DocumentRecordEvent.Closed) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                DocumentRecordEffect.Back -> onBack()
                DocumentRecordEffect.VerifyAccount -> onVerify()
                DocumentRecordEffect.ScrollToStart -> list.requestScrollToItem(0)
            }
        }
        DocumentRecordPage(state, list, viewModel::onEvent)
    }
}
