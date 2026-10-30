@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.documents.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.documents.presentation.changes.*
import dev.fajar.starter.documents.presentation.changes.pages.DocumentMetadataChangesPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

internal fun NavGraphBuilder.documentMetadataChangesRoute(
    workspaceId: String,
    onEdit: (String) -> Unit,
    onDetails: (String) -> Unit,
    onVerify: () -> Unit,
    onBack: () -> Unit,
) {
    composable<DocumentMetadataChangesRoute> {
        val viewModel =
            koinViewModel<DocumentMetadataChangesViewModel>(
                parameters = { parametersOf(workspaceId) }
            )
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(DocumentMetadataChangesEvent.Opened)
            onStopOrDispose { viewModel.onEvent(DocumentMetadataChangesEvent.Closed) }
        }
        BackHandler { viewModel.onEvent(DocumentMetadataChangesEvent.BackRequested) }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                is DocumentMetadataChangesEffect.Editor -> onEdit(effect.documentId)
                is DocumentMetadataChangesEffect.Recovery -> onDetails(effect.documentId)
                DocumentMetadataChangesEffect.VerifyAccount -> onVerify()
                DocumentMetadataChangesEffect.ScrollToStart -> list.requestScrollToItem(0)
                DocumentMetadataChangesEffect.Back -> onBack()
            }
        }
        DocumentMetadataChangesPage(state, list, viewModel::onEvent)
    }
}
