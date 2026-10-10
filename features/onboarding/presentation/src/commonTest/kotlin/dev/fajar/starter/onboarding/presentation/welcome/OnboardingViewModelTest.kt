@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.onboarding.presentation.welcome

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.onboarding.domain.repositories.OnboardingRepository
import dev.fajar.starter.onboarding.domain.usecases.CompleteOnboarding
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*

class OnboardingViewModelTest {
    private val store = ViewModelStore()

    @BeforeTest
    fun before() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun after() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun failedPersistenceCanBeRetriedWithoutSkippingOnboarding() = runTest {
        var fail = true
        val repository =
            object : OnboardingRepository {
                override suspend fun isComplete() = AppResult.Success(false)

                override suspend fun complete(): AppResult<Unit> =
                    if (fail) AppResult.Failed(Failure(FailureKind.Storage, "Try again"))
                    else AppResult.Success(Unit)
            }
        val viewModel = OnboardingViewModel(CompleteOnboarding(repository))
        store.put("welcome", viewModel)
        viewModel.onEvent(OnboardingEvent.FinishRequested)
        runCurrent()
        assertFalse(viewModel.state.value.completed)
        assertEquals("Try again", viewModel.state.value.error)
        fail = false
        viewModel.onEvent(OnboardingEvent.FinishRequested)
        runCurrent()
        assertTrue(viewModel.state.value.completed)
        assertNull(viewModel.state.value.error)
    }
}
