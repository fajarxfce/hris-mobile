package dev.fajar.starter.availability.presentation.gate

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.availability.domain.entities.AppAvailability
import dev.fajar.starter.availability.domain.usecases.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class AvailabilityViewModel(
    private val observe: ObserveAvailability,
    private val refresh: RefreshAvailability,
    private val dismiss: DismissRecommendedUpdate,
    build: AppBuild,
) :
    MviViewModel<AvailabilityState, AvailabilityEvent, AvailabilityEffect>(
        AvailabilityState(updateUrl = build.updateUrl)
    ) {
    private var observation: Job? = null

    init {
        on<AvailabilityEvent.ObserveRequested>(::onObserveRequested)
        on<AvailabilityEvent.RefreshRequested>(::onRefreshRequested)
        on<AvailabilityEvent.DismissRequested>(::onDismissRequested)
        on<AvailabilityEvent.UpdateOpeningFailed>(::onUpdateOpeningFailed)
        on<AvailabilityEvent.UpdateRequested>(::onUpdateRequested)
        onEvent(AvailabilityEvent.ObserveRequested)
    }

    private fun onObserveRequested(event: AvailabilityEvent.ObserveRequested) {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                observe().collect { result ->
                    when (result) {
                        is AppResult.Success ->
                            updateState {
                                it.copy(
                                    loading = false,
                                    availability = result.value,
                                    failure = null,
                                )
                            }
                        is AppResult.Failed ->
                            updateState { it.copy(loading = false, failure = result.failure) }
                    }
                }
            }
    }

    private fun onRefreshRequested(event: AvailabilityEvent.RefreshRequested) {
        if (state.value.refreshing) return
        updateState { it.copy(refreshing = true, failure = null) }
        viewModelScope.launch {
            when (val result = refresh()) {
                is AppResult.Success -> {
                    updateState { it.copy(refreshing = false) }
                    onEvent(AvailabilityEvent.ObserveRequested)
                }
                is AppResult.Failed ->
                    updateState { it.copy(refreshing = false, failure = result.failure) }
            }
        }
    }

    private fun onDismissRequested(event: AvailabilityEvent.DismissRequested) {
        val recommendation =
            state.value.availability as? AppAvailability.UpdateRecommended ?: return
        viewModelScope.launch {
            when (val result = dismiss(recommendation.build)) {
                is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
                is AppResult.Success -> Unit
            }
        }
    }

    private fun onUpdateOpeningFailed(event: AvailabilityEvent.UpdateOpeningFailed) {
        updateState {
            it.copy(
                failure = Failure(FailureKind.Unavailable, "The update page could not be opened.")
            )
        }
    }

    private fun onUpdateRequested(event: AvailabilityEvent.UpdateRequested) {
        val url = state.value.updateUrl ?: return
        viewModelScope.launch { emitEffect(AvailabilityEffect.OpenUpdate(url)) }
    }
}
