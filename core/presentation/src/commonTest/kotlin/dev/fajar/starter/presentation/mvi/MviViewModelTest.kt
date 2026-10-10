@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.presentation.mvi

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class MviViewModelTest {
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
    fun bufferedEffectsSurviveCollectorAbsenceAndDoNotReplay() = runTest {
        val viewModel = SampleViewModel()
        store.put("sample", viewModel)
        viewModel.onEvent(Event.Increment)
        viewModel.onEvent(Event.Notify)
        runCurrent()
        assertEquals(1, viewModel.effects.first())
        val next = backgroundScope.async { viewModel.effects.first() }
        runCurrent()
        assertFalse(next.isCompleted)
        viewModel.onEvent(Event.Increment)
        viewModel.onEvent(Event.Notify)
        runCurrent()
        assertEquals(2, next.await())
    }

    @Test
    fun disposalCancelsPendingWorkAndIgnoresFurtherEvents() = runTest {
        val viewModel = SampleViewModel()
        store.put("sample", viewModel)
        viewModel.onEvent(Event.Wait)
        runCurrent()
        store.clear()
        viewModel.onEvent(Event.Increment)
        runCurrent()
        assertTrue(viewModel.cancelled)
        assertEquals(0, viewModel.state.value)
    }

    private sealed interface Event {
        data object Increment : Event

        data object Notify : Event

        data object Wait : Event
    }

    private class SampleViewModel : MviViewModel<Int, Event, Int>(0) {
        var cancelled = false

        init {
            on<Event.Increment> { updateState { it + 1 } }
            on<Event.Notify> { viewModelScope.launch { emitEffect(state.value) } }
            on<Event.Wait> {
                viewModelScope.launch {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        }
    }
}
