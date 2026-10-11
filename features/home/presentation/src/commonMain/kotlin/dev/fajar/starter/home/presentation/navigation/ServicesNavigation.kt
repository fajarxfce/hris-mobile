package dev.fajar.starter.home.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.home.presentation.catalog.HomeService
import dev.fajar.starter.home.presentation.services.*
import dev.fajar.starter.home.presentation.services.pages.ServicesPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.servicesRoutes(
    workspaceId: String,
    onService: (HomeService) -> Unit,
    onBack: () -> Unit,
) {
    composable<ServicesRoute> {
        val viewModel = koinViewModel<ServicesViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                is ServicesEffect.OpenService -> onService(effect.service)
                ServicesEffect.Back -> onBack()
            }
        }
        ServicesPage(state, viewModel::onEvent)
    }
}
