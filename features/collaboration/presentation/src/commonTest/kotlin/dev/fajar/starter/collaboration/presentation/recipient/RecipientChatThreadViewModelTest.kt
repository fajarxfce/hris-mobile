@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.recipient

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class RecipientChatThreadViewModelTest {
    @Test
    fun rapidSendsRenderBeforeLocalHandoffAndKeepLaterTypingIndependent() = runTest {
        withModel { f, model, _ ->
            val release = CompletableDeferred<Unit>()
            f.editor.beforeHandoff = { release.await() }
            try {
                for (text in listOf("AAA", "BBB", "CCC")) {
                    model.onEvent(RecipientChatThreadEvent.TextChanged(text))
                    model.onEvent(RecipientChatThreadEvent.SendRequested)
                    runCurrent()
                    assertEquals("", model.state.value.editor.text)
                    assertEquals(text, model.state.value.rows.first().bubble.text)
                }
                assertTrue(f.editor.handed.isEmpty())
                assertEquals(
                    listOf("CCC", "BBB", "AAA"),
                    model.state.value.rows.map { it.bubble.text },
                )
                assertTrue(model.state.value.rows.all { it.canonicalIdentity == null })
                model.onEvent(RecipientChatThreadEvent.TextChanged("Later typing"))
                release.complete(Unit)
                model.state.first {
                    it.editor.submissions.size == 3 &&
                        it.editor.submissions.all { row -> row.durable }
                }
                assertEquals("Later typing", model.state.value.editor.text)
                assertEquals(3, model.state.value.rows.size)
                assertEquals(0, f.thread.schedules)
                assertEquals(0, f.metadataReads)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun passiveReentryRetainsOneSourceAndDoesNotAcquireCanonicalSetup() = runTest {
        withModel { f, model, _ ->
            model.onEvent(RecipientChatThreadEvent.TextChanged("Draft"))
            advanceTimeBy(250)
            runCurrent()
            repeat(10) {
                model.onEvent(RecipientChatThreadEvent.Stopped)
                model.onEvent(RecipientChatThreadEvent.Entered)
            }
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals("Draft", model.state.value.editor.text)
            assertEquals(1, f.sourceStarts)
            assertEquals(1, f.sourceReaders)
            assertEquals(0, f.metadataReads)
            assertEquals(0, f.thread.recordReaders)
            assertEquals(0, f.thread.schedules)
        }
    }

    @Test
    fun canonicalAssociationAndTerminalProgressKeepTheSameBubbleAfterSourceRetirement() = runTest {
        withModel { f, model, _ ->
            model.onEvent(RecipientChatThreadEvent.TextChanged("Original"))
            model.onEvent(RecipientChatThreadEvent.SendRequested)
            model.state.first { it.editor.submissions.singleOrNull()?.durable == true }
            val before = model.state.value.rows.single()
            val original = f.editor.handed.single()
            f.associate()
            model.state.first { it.rows.singleOrNull()?.canonicalIdentity != null }
            f.thread.messages.value = listOf(f.sent(original))
            model.state.first {
                it.rows.singleOrNull()?.bubble?.delivery == CollaborationMessageDelivery.SENT
            }
            f.editor.originals.clear()
            f.publishSource()
            runCurrent()
            val after = model.state.value.rows.single()
            assertEquals(before.identity, after.identity)
            assertEquals(before.position, after.position)
            assertEquals(before.bubble.timestamp, after.bubble.timestamp)
            assertEquals(f.metadataValue.id, after.canonicalIdentity?.conversationId)
            assertEquals(CollaborationMessageDelivery.SENT, after.bubble.delivery)
            assertEquals(1, f.thread.recordReaders)
            assertEquals(1, f.metadataReads)
            assertEquals(0, f.thread.schedules)
        }
    }

    @Test
    fun transientSourceFailureKeepsVisibleMessagesAndTypedInput() = runTest {
        withModel { f, model, _ ->
            model.onEvent(RecipientChatThreadEvent.TextChanged("Original"))
            model.onEvent(RecipientChatThreadEvent.SendRequested)
            model.state.first { it.editor.submissions.singleOrNull()?.durable == true }
            model.onEvent(RecipientChatThreadEvent.TextChanged("Later"))
            val before = model.state.value.rows
            f.sourceFailure = f.thread.storage
            f.publishSource()
            model.state.first { it.sourceFailure != null }
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(before, model.state.value.rows)
            assertEquals("Later", model.state.value.editor.text)
            assertEquals(1, f.sourceStarts)
            f.sourceFailure = null
            f.publishSource()
            model.state.first { it.sourceFailure == null }
            assertEquals(before, model.state.value.rows)
        }
    }

    @Test
    fun aDeniedReaderFencesLateSuccessFromAnotherReaderUntilNewEntry() = runTest {
        withModel { f, model, _ ->
            f.associate()
            model.onEvent(RecipientChatThreadEvent.TextChanged("Private"))
            model.onEvent(RecipientChatThreadEvent.SendRequested)
            model.state.first {
                it.editor.submissions.singleOrNull()?.durable == true && it.thread != null
            }
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
                f.thread.recordFailure = f.thread.denial
                f.thread.messages.value = listOf(f.sent(f.editor.handed.single()))
                model.state.first { it.restriction == f.thread.denial }
                release.complete(Unit)
                runCurrent()
                assertTrue(model.state.value.rows.isEmpty())
                assertFalse(model.state.value.editor.ready)
                assertEquals(0, f.sourceReaders)
                assertEquals(0, f.thread.recordReaders)
                f.beforeSource = {}
                f.thread.recordFailure = null
                model.onEvent(RecipientChatThreadEvent.Entered)
                model.state.first { it.editor.ready && it.rows.isNotEmpty() }
                assertNull(model.state.value.restriction)
                assertEquals(1, f.maxSourceReaders)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun replacementAndDisposalCannotKeepOldRowsOrReaderOwners() = runTest {
        withModel { f, model, store ->
            f.associate()
            model.onEvent(RecipientChatThreadEvent.TextChanged("Old account"))
            model.onEvent(RecipientChatThreadEvent.SendRequested)
            model.state.first { it.rows.isNotEmpty() && it.editor.submissions.single().durable }
            f.thread.base.session.value = null
            model.state.first { it.restriction != null }
            runCurrent()
            assertTrue(model.state.value.rows.isEmpty())
            assertFalse(model.state.value.editor.ready)
            assertEquals(0, f.sourceReaders)
            assertEquals(0, f.bindingReaders)
            assertEquals(0, f.thread.recordReaders)
            store.clear()
            runCurrent()
            val stopped = model.state.value
            f.publishSource()
            runCurrent()
            assertEquals(stopped, model.state.value)
        }
    }

    @Test
    fun exactHistoryKeepsOneBubbleWhenActorAndSourceOriginalsDisappear() = runTest {
        withModel { f, model, _ ->
            model.onEvent(RecipientChatThreadEvent.TextChanged("Original"))
            model.onEvent(RecipientChatThreadEvent.SendRequested)
            model.state.first { it.editor.submissions.singleOrNull()?.durable == true }
            val before = model.state.value.rows.single()
            val original = f.editor.handed.single()
            f.associate()
            f.bindRoom()
            val record = f.sent(original)
            f.thread.messages.value = listOf(record)
            f.publishHistory(record)
            model.state.first {
                it.messages.represented.size == 1 && it.editor.submissions.isEmpty()
            }
            f.editor.originals.clear()
            f.publishSource()
            f.thread.messages.value = emptyList()
            runCurrent()
            val after = model.state.value.rows.single()
            assertEquals(before.identity, after.identity)
            assertEquals(before.position, after.position)
            assertEquals(CollaborationMessageDelivery.SENT, after.bubble.delivery)
            assertEquals(0, f.thread.recordReaders)
            assertNull(model.state.value.projectionFailure)
        }
    }

    @Test
    fun aWriteDenialCannotBeReopenedByALateSourcePublication() = runTest {
        withModel { f, model, _ ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeSource = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            f.editor.afterHandoff = {
                entered.await()
                f.editor.currentAdmission =
                    AppResult.Success(
                        CollaborationSpaceDirectoryAdmission(chatId(650), f.thread.denial)
                    )
            }
            try {
                model.onEvent(RecipientChatThreadEvent.TextChanged("Retained"))
                model.onEvent(RecipientChatThreadEvent.SendRequested)
                model.state.first { it.restriction == f.thread.denial }
                f.editor.currentAdmission =
                    AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
                release.complete(Unit)
                runCurrent()
                assertTrue(model.state.value.rows.isEmpty())
                assertFalse(model.state.value.editor.ready)
                assertEquals(0, f.sourceReaders)
                f.beforeSource = {}
                f.editor.afterHandoff = {}
                model.onEvent(RecipientChatThreadEvent.Entered)
                model.state.first { it.editor.ready && it.rows.isNotEmpty() }
                assertEquals(1, f.editor.handed.size)
                assertEquals(f.editor.handed.single(), f.editor.originals.values.single())
                assertEquals(1, f.maxSourceReaders)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun disposalFencesALateHeldHandoffWithoutPublishingBackIntoTheScreen() = runTest {
        withModel { f, model, store ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.editor.beforeHandoff = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                model.onEvent(RecipientChatThreadEvent.TextChanged("Closing"))
                model.onEvent(RecipientChatThreadEvent.SendRequested)
                entered.await()
                store.clear()
                runCurrent()
                val closed = model.state.value
                assertTrue(closed.rows.isEmpty())
                assertNull(closed.source)
                assertNull(closed.thread)
                assertFalse(closed.editor.ready)
                release.complete(Unit)
                runCurrent()
                assertEquals(closed, model.state.value)
                assertEquals(0, f.sourceReaders)
                assertEquals(0, f.bindingReaders)
                assertEquals(0, f.thread.recordReaders)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun preparationCanReadWhileHiddenButCannotSendOrType() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = RecipientChatThreadViewModelFixture()
        val model = f.model()
        val store = ViewModelStore().also { it.put("recipient-thread", model) }
        try {
            model.onEvent(RecipientChatThreadEvent.Prepared)
            model.state.first { it.editor.ready }
            model.onEvent(RecipientChatThreadEvent.TextChanged("Ignored"))
            model.onEvent(RecipientChatThreadEvent.SendRequested)
            runCurrent()
            assertFalse(model.state.value.visible)
            assertEquals("", model.state.value.editor.text)
            assertTrue(f.editor.handed.isEmpty())
            assertEquals(1, f.sourceStarts)
            assertEquals(0, f.metadataReads)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
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
        val store = ViewModelStore().also { it.put("recipient-thread", model) }
        try {
            assertEquals(0, f.sourceStarts)
            model.onEvent(RecipientChatThreadEvent.Entered)
            model.state.first { it.editor.ready }
            block(f, model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
