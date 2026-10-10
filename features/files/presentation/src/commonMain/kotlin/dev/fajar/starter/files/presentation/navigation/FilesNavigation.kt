package dev.fajar.starter.files.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.files.presentation.queue.*
import dev.fajar.starter.files.presentation.queue.pages.FilesPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel

fun NavGraphBuilder.filesRoutes(onBack: () -> Unit) {
    composable<FilesRoute> {
        val viewModel = koinViewModel<FilesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(FilesEvent.Started)
            onStopOrDispose { viewModel.onEvent(FilesEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                FilesEffect.Back -> onBack()
            }
        }
        FilesPage(state, viewModel::onEvent)
    }
}
