package dev.fajar.starter.documents.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.documents.presentation.catalog.*
import dev.fajar.starter.documents.presentation.catalog.pages.DocumentCatalogPage
import dev.fajar.starter.documents.presentation.detail.*
import dev.fajar.starter.documents.presentation.detail.pages.DocumentFilePage
import dev.fajar.starter.documents.presentation.download.*
import dev.fajar.starter.documents.presentation.download.pages.DocumentDownloadPage
import dev.fajar.starter.documents.presentation.files.*
import dev.fajar.starter.documents.presentation.files.pages.DocumentFilesPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.documentRoutes(
    workspaceId: String,
    onFile: (String) -> Unit,
    onCatalog: () -> Unit,
    onDownload: (String, Boolean) -> Unit,
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
                DocumentFilesEffect.OpenCatalog -> onCatalog()
                is DocumentFilesEffect.OpenDownload -> onDownload(effect.id, false)
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
    composable<DocumentCatalogRoute> {
        val viewModel =
            koinViewModel<DocumentCatalogViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        val employments = rememberLazyListState()
        DisposableEffect(viewModel) {
            viewModel.onEvent(DocumentCatalogEvent.Opened)
            onDispose { viewModel.onEvent(DocumentCatalogEvent.Closed) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                is DocumentCatalogEffect.OpenDownload -> onDownload(effect.fileId, true)
                DocumentCatalogEffect.Back -> onBack()
                DocumentCatalogEffect.VerifyAccount -> onVerify()
                DocumentCatalogEffect.ScrollToStart -> list.requestScrollToItem(0)
                DocumentCatalogEffect.ScrollEmploymentsToStart -> employments.requestScrollToItem(0)
            }
        }
        DocumentCatalogPage(state, list, employments, viewModel::onEvent)
    }
    composable<DocumentDownloadRoute> { entry ->
        val route = entry.toRoute<DocumentDownloadRoute>()
        val viewModel =
            koinViewModel<DocumentDownloadViewModel>(
                parameters = {
                    parametersOf(DocumentDownloadTarget(workspaceId, route.fileId, route.start))
                }
            )
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        // The system save dialog may stop the Activity; its operation belongs to this route.
        DisposableEffect(viewModel) {
            viewModel.onEvent(DocumentDownloadEvent.Opened)
            onDispose { viewModel.onEvent(DocumentDownloadEvent.Closed) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                DocumentDownloadEffect.Back -> onBack()
                DocumentDownloadEffect.VerifyAccount -> onVerify()
            }
        }
        DocumentDownloadPage(state, list, viewModel::onEvent)
    }
}
