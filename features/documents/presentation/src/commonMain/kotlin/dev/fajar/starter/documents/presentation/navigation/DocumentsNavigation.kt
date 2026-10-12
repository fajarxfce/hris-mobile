package dev.fajar.starter.documents.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.documents.presentation.detail.*
import dev.fajar.starter.documents.presentation.detail.pages.DocumentFilePage
import dev.fajar.starter.documents.presentation.files.*
import dev.fajar.starter.documents.presentation.files.pages.DocumentFilesPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.documentRoutes(
    workspaceId: String,
    onFile: (String) -> Unit,
    onVerify: () -> Unit,
    onBack: () -> Unit,
) {
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
                is DocumentFilesEffect.OpenFile -> onFile(effect.id)
            }
        }
        DocumentFilesPage(state, list, viewModel::onEvent)
    }
    composable<DocumentFileRoute> { entry ->
        val route = entry.toRoute<DocumentFileRoute>()
        val viewModel =
            koinViewModel<DocumentFileViewModel>(
                parameters = { parametersOf(DocumentFileTarget(workspaceId, route.fileId)) }
            )
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        val employments = rememberLazyListState()
        DisposableEffect(viewModel) {
            viewModel.onEvent(DocumentFileEvent.Opened)
            onDispose { viewModel.onEvent(DocumentFileEvent.Closed) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                DocumentFileEffect.Back -> onBack()
                DocumentFileEffect.VerifyAccount -> onVerify()
                DocumentFileEffect.ScrollEmploymentsToStart -> employments.requestScrollToItem(0)
            }
        }
        DocumentFilePage(state, list, employments, viewModel::onEvent)
    }
}
