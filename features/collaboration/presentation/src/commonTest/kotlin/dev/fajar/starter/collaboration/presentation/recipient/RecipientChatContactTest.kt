@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.recipient

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class RecipientChatContactTest {
    @Test
    fun preAssociationNameUsesOneLocalReaderAcrossPassiveReentry() = runTest {
        withModel { f, model, _ ->
            assertEquals("Bima", model.state.value.contact?.displayName)
            repeat(10) {
                model.onEvent(RecipientChatThreadEvent.Stopped)
                model.onEvent(RecipientChatThreadEvent.Entered)
                runCurrent()
            }
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, f.editor.contactReads)
            assertEquals(1, f.editor.contactReaders)
            assertEquals(0, f.metadataReads)
            assertEquals(0, f.thread.schedules)
            assertNull(model.state.value.destination)
        }
    }

    @Test
    fun labelChangesDoNotRebuildBubblesOrReplaceTheEditingSession() = runTest {
        withModel { f, model, _ ->
            model.onEvent(RecipientChatThreadEvent.TextChanged("Original"))
            model.onEvent(RecipientChatThreadEvent.SendRequested)
            model.state.first { it.editor.submissions.singleOrNull()?.durable == true }
            model.onEvent(RecipientChatThreadEvent.TextChanged("Later typing"))
            runCurrent()
            assertEquals("Later typing", model.state.value.editor.text)
            val before = model.state.value
            f.editor.contactFacts.value =
                AppResult.Success(f.editor.contact.copy(displayName = "Bima Santoso"))
            model.state.first { it.contact?.displayName == "Bima Santoso" }
            assertSame(before.rows, model.state.value.rows)
            assertSame(before.editor, model.state.value.editor)
            assertEquals(1, f.sourceStarts)
            assertEquals(1, f.editor.handed.size)
        }
    }

    @Test
    fun transientLabelFailureRetainsTheNameAndExplicitRemovalClearsIt() = runTest {
        withModel { f, model, _ ->
            val before = model.state.value
            f.editor.contactFacts.value = AppResult.Failed(f.thread.storage)
            model.state.first { it.contactFailure != null }
            assertEquals(before.contact, model.state.value.contact)
            assertSame(before.rows, model.state.value.rows)
            assertNull(model.state.value.sourceFailure)
            assertNull(model.state.value.restriction)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, f.editor.contactReads)
            f.editor.contactFacts.value = AppResult.Success(null)
            model.state.first { it.contact == null && it.contactFailure == null }
            assertTrue(model.state.value.canCompose)
        }
    }

    @Test
    fun labelDenialFencesLateSourceResultsAndReadmissionReacquiresLocally() = runTest {
        withModel { f, model, _ ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeSource = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            f.publishSource()
            entered.await()
            try {
                f.editor.contactFacts.value = AppResult.Failed(f.thread.denial)
                model.state.first { it.restriction == f.thread.denial }
                release.complete(Unit)
                runCurrent()
                assertNull(model.state.value.contact)
                assertTrue(model.state.value.rows.isEmpty())
                assertFalse(model.state.value.editor.ready)
                assertEquals(0, f.editor.contactReaders)
                assertEquals(0, f.sourceReaders)
                f.beforeSource = {}
                f.editor.contactFacts.value = AppResult.Success(f.editor.contact)
                model.onEvent(RecipientChatThreadEvent.Entered)
                model.state.first { it.contact != null && it.editor.ready }
                assertEquals(1, f.editor.contactReaders)
                assertEquals(1, f.editor.maximumContactReaders)
                assertEquals(0, f.thread.schedules)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun disposalJoinsLateContactAndClearsPrivateState() = runTest {
        withModel { f, model, store ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.editor.beforeContact = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            f.editor.contactFacts.value =
                AppResult.Success(f.editor.contact.copy(displayName = "Later"))
            entered.await()
            try {
                store.clear()
                runCurrent()
                assertNull(model.state.value.contact)
                val closed = model.state.value
                release.complete(Unit)
                runCurrent()
                assertEquals(closed, model.state.value)
                assertEquals(0, f.editor.contactReaders)
                assertEquals(0, f.sourceReaders)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun canonicalAssociationKeepsTwoExplicitContactSubscriptionsBounded() = runTest {
        withModel { f, model, _ ->
            f.associate()
            model.state.first { it.thread != null }
            assertEquals(
                AppResult.Success(f.editor.contact),
                model.state.value.thread?.participants?.contact,
            )
            assertEquals(2, f.editor.contactReaders)
            repeat(5) {
                model.onEvent(RecipientChatThreadEvent.Stopped)
                model.onEvent(RecipientChatThreadEvent.Entered)
                runCurrent()
            }
            assertEquals(2, f.editor.contactReads)
            assertEquals(2, f.editor.maximumContactReaders)
            assertEquals(1, f.metadataReads)
            assertEquals(0, f.thread.schedules)
        }
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(
                RecipientChatThreadViewModelFixture, RecipientChatThreadViewModel, ViewModelStore,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = RecipientChatThreadViewModelFixture()
        val model = f.model()
        val store = ViewModelStore().also { it.put("recipient-contact", model) }
        try {
            model.onEvent(RecipientChatThreadEvent.Entered)
            model.state.first { it.editor.ready && it.contact != null }
            block(f, model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
