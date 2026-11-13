@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.spacethread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.CollaborationComposerOutboxEntry
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class SpaceChatThreadRetentionTest {
    @Test
    fun releasingAndReopeningRetainsRowsDraftAndTheExistingLocalReaders() = runTest {
        withModel { f, model ->
            f.entries.value = listOf(CollaborationComposerOutboxEntry(f.submission(), false))
            model.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            model.onEvent(SpaceChatThreadEvent.DraftChanged("Keep my draft"))
            runCurrent()
            val rows = model.state.value.rows
            model.onEvent(SpaceChatThreadEvent.MessageSelected(rows.single().identity))
            runCurrent()
            assertTrue(model.state.value.selecting)
            model.onEvent(SpaceChatThreadEvent.RouteReleased)
            runCurrent()
            assertFalse(model.state.value.selecting)
            assertFalse(model.state.value.leaving)
            assertSame(rows, model.state.value.rows)
            assertEquals("Keep my draft", model.state.value.draft)
            model.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertSame(rows, model.state.value.rows)
            assertEquals("Keep my draft", model.state.value.draft)
            assertEquals(1, f.draftStarts)
            assertEquals(1, f.outboxReaders)
            assertEquals(1, f.content.bindingReaders)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun permanentReleaseDiscardsBufferedCopyAndBackBeforeTheNextEntryCollects() = runTest {
        withModel { f, model ->
            f.entries.value = listOf(CollaborationComposerOutboxEntry(f.submission(), false))
            model.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            model.onEvent(
                SpaceChatThreadEvent.MessageSelected(model.state.value.rows.single().identity)
            )
            model.onEvent(SpaceChatThreadEvent.CopySelected)
            runCurrent()
            model.onEvent(SpaceChatThreadEvent.BackRequested)
            runCurrent()
            assertTrue(model.state.value.leaving)
            model.onEvent(SpaceChatThreadEvent.RouteReleased)
            model.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            val effects = mutableListOf<SpaceChatThreadEffect>()
            val collector = backgroundScope.launch { model.effects.collect { effects += it } }
            runCurrent()
            assertTrue(effects.isEmpty())
            assertFalse(model.state.value.leaving)
            assertTrue(model.state.value.canCompose)
            collector.cancel()
        }
    }

    @Test
    fun releasingADepartureKeepsItsInFlightDraftWriteWithoutALateBackEffect() = runTest {
        withModel { f, model ->
            val release = CompletableDeferred<Unit>()
            f.beforeEdit = { release.await() }
            val effects = mutableListOf<SpaceChatThreadEffect>()
            val collector = backgroundScope.launch { model.effects.collect { effects += it } }
            try {
                model.onEvent(SpaceChatThreadEvent.Entered)
                runCurrent()
                model.onEvent(SpaceChatThreadEvent.DraftChanged("Still saving locally"))
                runCurrent()
                advanceTimeBy(300)
                runCurrent()
                assertTrue(model.state.value.draftSaving)
                model.onEvent(SpaceChatThreadEvent.BackRequested)
                runCurrent()
                assertTrue(model.state.value.leaving)
                model.onEvent(SpaceChatThreadEvent.RouteReleased)
                runCurrent()
                assertFalse(model.state.value.leaving)
                assertEquals("Still saving locally", model.state.value.draft)
                release.complete(Unit)
                advanceUntilIdle()
                assertEquals("Still saving locally", f.draft.value?.text)
                assertTrue(effects.isEmpty())
                model.onEvent(SpaceChatThreadEvent.Entered)
                runCurrent()
                assertEquals("Still saving locally", model.state.value.draft)
                assertEquals(1, f.draftStarts)
            } finally {
                release.complete(Unit)
                collector.cancel()
            }
        }
    }

    @Test
    fun aLateClipboardAdmissionCannotCrossPermanentReleaseAndReentry() = runTest {
        withModel { f, model ->
            f.entries.value = listOf(CollaborationComposerOutboxEntry(f.submission(), false))
            model.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            val effect = async { model.effects.first() }
            model.onEvent(
                SpaceChatThreadEvent.MessageSelected(model.state.value.rows.single().identity)
            )
            model.onEvent(SpaceChatThreadEvent.CopySelected)
            runCurrent()
            val copy = assertIs<SpaceChatThreadEffect.CopyMessages>(effect.await())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeMetadata = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val confirmation = async { model.confirmCopy(copy) }
            try {
                runCurrent()
                assertTrue(entered.isCompleted)
                model.onEvent(SpaceChatThreadEvent.RouteReleased)
                model.onEvent(SpaceChatThreadEvent.Entered)
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertFalse(confirmation.await())
                assertFalse(model.confirmCopy(copy))
                assertEquals(1, model.state.value.rows.size)
            } finally {
                release.complete(Unit)
                confirmation.cancel()
            }
        }
    }

    private suspend fun TestScope.withModel(
        block: suspend (SpaceChatThreadFixture, SpaceChatThreadViewModel) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = SpaceChatThreadFixture()
        val model = fixture.model()
        val store = ViewModelStore().also { it.put("thread", model) }
        try {
            block(fixture, model)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
