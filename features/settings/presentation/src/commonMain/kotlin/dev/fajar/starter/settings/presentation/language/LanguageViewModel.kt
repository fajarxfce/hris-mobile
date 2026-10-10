package dev.fajar.starter.settings.presentation.language

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.settings.domain.usecases.ObserveLanguage
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

/** Root observation contains only the preference that changes localization. */
@KoinViewModel
class LanguageViewModel(observeLanguage: ObserveLanguage) :
    MviViewModel<AppLanguage, Nothing, Nothing>(AppLanguage.System) {
    init {
        viewModelScope.launch {
            observeLanguage().collect { result ->
                if (result is AppResult.Success) updateState { result.value }
            }
        }
    }
}
