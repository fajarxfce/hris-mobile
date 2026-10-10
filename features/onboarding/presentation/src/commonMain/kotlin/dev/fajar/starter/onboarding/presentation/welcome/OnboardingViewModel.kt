package dev.fajar.starter.onboarding.presentation.welcome

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.onboarding.domain.usecases.CompleteOnboarding
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class OnboardingViewModel(private val completeOnboarding: CompleteOnboarding) :
    MviViewModel<OnboardingState, OnboardingEvent, OnboardingEffect>(OnboardingState()) {

    init {
        on<OnboardingEvent.NextRequested>(::onNextRequested)
        on<OnboardingEvent.BackRequested>(::onBackRequested)
        on<OnboardingEvent.FinishRequested>(::onFinishRequested)
    }

    private fun onNextRequested(event: OnboardingEvent.NextRequested) {
        if (state.value.saving) return
        if (state.value.step < 2) {
            updateState { it.copy(step = it.step + 1) }
        } else {
            onFinishRequested(OnboardingEvent.FinishRequested)
        }
    }

    private fun onBackRequested(event: OnboardingEvent.BackRequested) {
        if (!state.value.saving) updateState { it.copy(step = (it.step - 1).coerceAtLeast(0)) }
    }

    private fun onFinishRequested(event: OnboardingEvent.FinishRequested) {
        if (state.value.saving || state.value.completed) return
        updateState { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val result = completeOnboarding()) {
                is AppResult.Success -> {
                    updateState { it.copy(saving = false, completed = true) }
                    emitEffect(OnboardingEffect.Completed)
                }
                is AppResult.Failed ->
                    updateState { it.copy(saving = false, error = result.failure.message) }
            }
        }
    }
}
