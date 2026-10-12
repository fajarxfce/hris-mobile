package dev.fajar.starter.documents.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.documents.presentation.files.*
import dev.fajar.starter.documents.presentation.files.pages.DocumentFilesPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.documentRoutes(workspaceId: String, onVerify: () -> Unit, onBack: () -> Unit) {
    composable<DocumentsRoute> {
        val viewModel =
            koinViewModel<DocumentFilesViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        // External pickers stop the Activity. The route, rather than ON_STOP, owns import lifetime.
        DisposableEffect(viewModel) {
            viewModel.onEvent(DocumentFilesEvent.Opened)
            onDispose { viewModel.onEvent(DocumentFilesEvent.Closed) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                DocumentFilesEffect.Back -> onBack()
                DocumentFilesEffect.VerifyAccount -> onVerify()
            }
        }
        DocumentFilesPage(state, list, viewModel::onEvent)
    }
}
