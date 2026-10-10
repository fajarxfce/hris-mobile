package dev.fajar.starter.availability.presentation.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fajar.starter.availability.domain.entities.AppAvailability
import dev.fajar.starter.availability.presentation.gate.*
import dev.fajar.starter.availability.presentation.gate.pages.AvailabilityPage
import dev.fajar.starter.availability.presentation.gate.widgets.UpdateBanner
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.observability.*
import dev.fajar.starter.presentation.mvi.CollectEffects
import kotlin.coroutines.cancellation.CancellationException
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AvailabilityGate(content: @Composable () -> Unit) {
    val viewModel = koinViewModel<AvailabilityViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is AvailabilityEffect.OpenUpdate ->
                try {
                    uriHandler.openUri(effect.url)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Diagnostics.failure(DiagnosticArea.Application, error)
                    viewModel.onEvent(AvailabilityEvent.UpdateOpeningFailed)
                }
        }
    }
    when {
        state.loading -> AppPage { AppLoading() }
        state.availability is AppAvailability.UpdateRequired ||
            state.availability is AppAvailability.Maintenance ->
            AvailabilityPage(state, viewModel::onEvent)
        else ->
            Column(Modifier.fillMaxSize()) {
                if (state.availability is AppAvailability.UpdateRecommended)
                    UpdateBanner(state, viewModel::onEvent)
                else if (state.failure != null)
                    AppCard {
                        AppFeedback(appString(AppString.PolicyRefreshFailed))
                        AppButton(
                            appString(AppString.Retry),
                            { viewModel.onEvent(AvailabilityEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                        )
                    }
                Box(Modifier.weight(1f)) { content() }
            }
    }
}
