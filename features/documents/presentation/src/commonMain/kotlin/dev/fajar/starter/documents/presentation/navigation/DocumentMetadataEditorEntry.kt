@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.documents.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.documents.presentation.editor.*
import dev.fajar.starter.documents.presentation.editor.pages.DocumentMetadataEditorPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

internal fun NavGraphBuilder.documentMetadataEditorRoute(
    workspaceId: String,
    onVerify: () -> Unit,
    onBack: () -> Unit,
) {
    composable<DocumentMetadataEditorRoute> { entry ->
        val route = entry.toRoute<DocumentMetadataEditorRoute>()
        val viewModel =
            koinViewModel<DocumentMetadataEditorViewModel>(
                parameters = {
                    parametersOf(DocumentMetadataEditorTarget(workspaceId, route.documentId))
                }
            )
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(DocumentMetadataEditorEvent.Opened)
            onStopOrDispose { viewModel.onEvent(DocumentMetadataEditorEvent.Stopped) }
        }
        BackHandler(enabled = state.dialog == null && !state.choosingCategory) {
            viewModel.onEvent(DocumentMetadataEditorEvent.BackRequested)
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                DocumentMetadataEditorEffect.Back -> onBack()
                DocumentMetadataEditorEffect.VerifyAccount -> onVerify()
                DocumentMetadataEditorEffect.ScrollToStart -> list.requestScrollToItem(0)
            }
        }
        DocumentMetadataEditorPage(state, list, viewModel::onEvent)
    }
}
