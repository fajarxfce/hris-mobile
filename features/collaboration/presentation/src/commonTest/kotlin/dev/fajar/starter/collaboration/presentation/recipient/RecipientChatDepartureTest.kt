@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.recipient

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class RecipientChatDepartureTest {
    @Test
    fun backFlushesTheTypedDraftBeforeOneConfirmedNavigationEffect() = runTest {
        withModel { f, model, effects ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.editor.beforeSave = {
                entered.complete(Unit)
                release.await()
            }
            try {
                model.onEvent(RecipientChatThreadEvent.TextChanged("Retain this draft"))
                model.onEvent(RecipientChatThreadEvent.BackRequested)
                entered.await()
                assertTrue(model.state.value.leaving)
                assertFalse(model.state.value.canCompose)
                assertTrue(effects.isEmpty())
                model.onEvent(RecipientChatThreadEvent.BackRequested)
                release.complete(Unit)
                runCurrent()
                assertEquals("Retain this draft", f.editor.saved.single().text)
                val effect = assertIs<RecipientChatThreadEffect.Back>(effects.single())
                assertTrue(model.confirmBack(effect))
                assertFalse(model.confirmBack(effect))
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun backWaitsForTheOriginalLocalHandoffWithoutResubmittingIt() = runTest {
        withModel { f, model, effects ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.editor.beforeHandoff = {
                entered.complete(Unit)
                release.await()
            }
            try {
                model.onEvent(RecipientChatThreadEvent.TextChanged("Original"))
                model.onEvent(RecipientChatThreadEvent.SendRequested)
                entered.await()
                val original = model.state.value.editor.submissions.single().original
                model.onEvent(RecipientChatThreadEvent.TextChanged("Later draft"))
                model.onEvent(RecipientChatThreadEvent.BackRequested)
                runCurrent()
                assertTrue(effects.isEmpty())
                release.complete(Unit)
                runCurrent()
                assertEquals(listOf(original), f.editor.handed)
                assertEquals("Later draft", f.editor.saved.last().text)
                assertTrue(
                    model.confirmBack(assertIs<RecipientChatThreadEffect.Back>(effects.single()))
                )
                assertEquals(0, f.thread.schedules)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun failedLocalFlushKeepsInputAndDoesNotEmitNavigation() = runTest {
        withModel { f, model, effects ->
            f.editor.currentAdmission = AppResult.Failed(f.thread.storage)
            model.onEvent(RecipientChatThreadEvent.TextChanged("Keep input"))
            model.onEvent(RecipientChatThreadEvent.BackRequested)
            model.state.first { !it.leaving && it.editor.failure != null }
            runCurrent()
            assertEquals("Keep input", model.state.value.editor.text)
            assertTrue(model.state.value.canCompose)
            assertTrue(effects.isEmpty())
            f.editor.currentAdmission = null
            model.onEvent(RecipientChatThreadEvent.RetryRequested)
            model.state.first { it.editor.failure == null && !it.editor.saving }
            model.onEvent(RecipientChatThreadEvent.BackRequested)
            runCurrent()
            assertEquals("Keep input", f.editor.saved.last().text)
            assertTrue(
                model.confirmBack(assertIs<RecipientChatThreadEffect.Back>(effects.single()))
            )
        }
    }

    @Test
    fun stoppingCancelsTheDepartureWaitButRetainsTheIndependentEditorWrite() = runTest {
        withModel { f, model, effects ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.editor.beforeSave = {
                entered.complete(Unit)
                release.await()
            }
            try {
                model.onEvent(RecipientChatThreadEvent.TextChanged("Saved while hidden"))
                model.onEvent(RecipientChatThreadEvent.BackRequested)
                entered.await()
                model.onEvent(RecipientChatThreadEvent.Stopped)
                runCurrent()
                assertFalse(model.state.value.leaving)
                release.complete(Unit)
                runCurrent()
                assertEquals("Saved while hidden", f.editor.saved.single().text)
                assertTrue(effects.isEmpty())
                model.onEvent(RecipientChatThreadEvent.Entered)
                model.onEvent(RecipientChatThreadEvent.BackRequested)
                runCurrent()
                assertTrue(
                    model.confirmBack(assertIs<RecipientChatThreadEffect.Back>(effects.single()))
                )
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun anAlreadyDeliveredEffectCannotNavigateAfterItsRouteStops() = runTest {
        withModel { _, model, effects ->
            model.onEvent(RecipientChatThreadEvent.BackRequested)
            runCurrent()
            val first = assertIs<RecipientChatThreadEffect.Back>(effects.single())
            model.onEvent(RecipientChatThreadEvent.RouteReleased)
            runCurrent()
            assertFalse(model.confirmBack(first))
            model.onEvent(RecipientChatThreadEvent.Entered)
            model.onEvent(RecipientChatThreadEvent.BackRequested)
            runCurrent()
            val next = assertIs<RecipientChatThreadEffect.Back>(effects.last())
            assertNotEquals(first.request, next.request)
            assertTrue(model.confirmBack(next))
        }
    }

    @Test
    fun denialCancelsAnOldDepartureButANewBackCanLeaveTheRestrictedPage() = runTest {
        withModel { f, model, effects ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.editor.beforeSave = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                model.onEvent(RecipientChatThreadEvent.TextChanged("Private"))
                model.onEvent(RecipientChatThreadEvent.BackRequested)
                entered.await()
                f.thread.deny()
                model.state.first { it.restriction != null }
                release.complete(Unit)
                runCurrent()
                assertTrue(effects.isEmpty())
                assertFalse(model.state.value.leaving)
                model.onEvent(RecipientChatThreadEvent.BackRequested)
                runCurrent()
                assertTrue(
                    model.confirmBack(assertIs<RecipientChatThreadEffect.Back>(effects.single()))
                )
            } finally {
                release.complete(Unit)
            }
        }
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(
                RecipientChatThreadViewModelFixture,
                RecipientChatThreadViewModel,
                MutableList<RecipientChatThreadEffect>,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = RecipientChatThreadViewModelFixture()
        val model = f.model()
        val store = ViewModelStore().also { it.put("recipient-thread", model) }
        val effects = mutableListOf<RecipientChatThreadEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.effects.collect { effects += it }
        }
        try {
            model.onEvent(RecipientChatThreadEvent.Entered)
            model.state.first { it.editor.ready }
            block(f, model, effects)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
