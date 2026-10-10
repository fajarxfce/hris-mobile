@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.settings.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.settings.domain.repositories.SettingsRepository
import dev.fajar.starter.settings.domain.usecases.ObserveLanguage
import dev.fajar.starter.settings.presentation.language.LanguageViewModel
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class LanguageViewModelTest {
    @BeforeTest
    fun before() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun after() {
        Dispatchers.resetMain()
    }

    @Test
    fun unrelatedSettingsAreNotObservedAndDisposalCancelsTheSubscription() = runTest {
        val updates = MutableSharedFlow<AppResult<AppLanguage>>()
        val repository =
            object : SettingsRepository {
                override fun observeLanguage() = updates

                override suspend fun setLanguage(language: AppLanguage): AppResult<Unit> =
                    error("Read-only observer")
            }
        val viewModel = LanguageViewModel(ObserveLanguage(repository))
        val owner = ViewModelStore().apply { put("language", viewModel) }
        val values = mutableListOf<AppLanguage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.toList(values)
        }
        runCurrent()
        assertEquals(1, updates.subscriptionCount.value)
        updates.emit(AppResult.Success(AppLanguage.Indonesian))
        updates.emit(AppResult.Success(AppLanguage.Indonesian))
        updates.emit(AppResult.Failed(Failure(FailureKind.Unexpected, "Storage unavailable")))
        runCurrent()
        assertEquals(listOf(AppLanguage.System, AppLanguage.Indonesian), values)
        owner.clear()
        runCurrent()
        assertEquals(0, updates.subscriptionCount.value)
    }
}
