@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ChatThreadQuotedReplyTest {
    @Test
    fun waitingConnectionKeepsAcceptedMessageAndNextDraftEditable() = runTest {
        withThread { f, vm, _, _ ->
            f.online()
            val delivering = CompletableDeferred<Unit>()
            f.beforeEnter = {
                delivering.complete(Unit)
                awaitCancellation()
            }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("First message"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertTrue(delivering.isCompleted)
            assertFalse(vm.state.value.saving)
            assertTrue(vm.state.value.canCompose)
            assertEquals("", vm.state.value.draft)
            assertEquals("First message", vm.state.value.rows.single { it.localId != null }.text)
            vm.onEvent(ChatThreadEvent.DraftChanged("Already typing the next message"))
            advanceTimeBy(251)
            runCurrent()
            assertEquals("Already typing the next message", f.draftSlot.value?.text)
            assertTrue(vm.state.value.canCompose)
        }
    }

    @Test
    fun longPressReplyKeepsTypedTextAndQueuesOneOfflineReply() = runTest {
        withThread { f, vm, _, effects ->
            f.historyRows.value = listOf(f.original())
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Typed before choosing reply"))
            choose(vm, vm.state.value.rows.single().key)
            runCurrent()
            assertFalse(vm.state.value.selecting)
            assertEquals("Typed before choosing reply", vm.state.value.draft)
            assertEquals(f.original().primaryEventId, vm.state.value.draftReplyToEventId)
            assertEquals(f.original().primaryEventId, f.draftSlot.value?.replyToEventId)
            assertTrue(ChatThreadEffect.FocusComposer in effects)
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            val record = f.records.value.single()
            assertEquals("Typed before choosing reply", record.intent.text)
            assertEquals(f.original().primaryEventId, record.intent.replyToEventId)
            assertEquals(CollaborationMessageState.Queued, record.state)
            assertNull(vm.state.value.draftReplyToEventId)
            assertEquals("", vm.state.value.draft)
            assertEquals(0, f.timelineOpens)
        }
    }

    @Test
    fun clearAndReplaceQuoteWhileAnAdmittedSaveWaitsRetainsLatestTextAndTarget() = runTest {
        withThread { f, vm, _, _ ->
            val first = f.original()
            val second =
                first.copy(
                    primaryEventId = "\$second",
                    eventIds = listOf("\$second"),
                    timestamp = 2000,
                    contentHash = "c".repeat(64),
                )
            f.historyRows.value = listOf(second, first)
            val release = CompletableDeferred<Unit>()
            f.afterDraftWrite = { if (f.draftWrites.size == 1) release.await() }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Same text"))
            choose(vm, vm.state.value.rows.single { it.eventId == first.primaryEventId }.key)
            runCurrent()
            assertEquals(first.primaryEventId, f.draftWrites.single().replyToEventId)
            vm.onEvent(ChatThreadEvent.ReplyCleared)
            runCurrent()
            assertNull(vm.state.value.draftReplyToEventId)
            assertEquals("Same text", vm.state.value.draft)
            choose(vm, vm.state.value.rows.single { it.eventId == second.primaryEventId }.key)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(
                listOf(first.primaryEventId, second.primaryEventId),
                f.draftWrites.map { it.replyToEventId },
            )
            assertEquals(second.primaryEventId, f.draftSlot.value?.replyToEventId)
            assertEquals("Same text", f.draftSlot.value?.text)
        }
    }

    @Test
    fun targetOutsideHistoryWindowDisappearsFromQuoteWithoutBlockingTheConversation() = runTest {
        withThread { f, vm, _, _ ->
            val original = f.original()
            f.olderQuoteRows.value = listOf(original)
            f.historyRows.value =
                listOf(
                    original.copy(
                        primaryEventId = "\$reply",
                        eventIds = listOf("\$reply"),
                        text = "Reply body",
                        replyToEventId = original.primaryEventId,
                    )
                )
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals(original.text, vm.state.value.quote(original.primaryEventId)?.text)
            assertEquals(1, f.activeQuotes)
            f.olderQuoteRows.value = listOf(original.copy(text = null, redacted = true))
            runCurrent()
            assertNull(vm.state.value.quote(original.primaryEventId))
            assertEquals("Reply body", vm.state.value.rows.single().text)
            assertFalse(vm.state.value.loading)
            assertEquals(0, f.timelineOpens)
        }
    }

    @Test
    fun restoredComposerReadsTheOriginalQuoteAndClearRemovesOnlyItsReference() = runTest {
        withThread { f, vm, store, _ ->
            f.historyRows.value = listOf(f.original())
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Retain this body"))
            choose(vm, vm.state.value.rows.single().key)
            runCurrent()
            val original = f.draftSlot.value!!
            store.clear()
            runCurrent()
            assertEquals(0, f.activeQuotes)
            val restored = f.model()
            store.put("thread", restored)
            restored.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals(original.text, restored.state.value.draft)
            assertEquals(original.replyToEventId, restored.state.value.draftReplyToEventId)
            assertFalse(restored.state.value.loading)
            restored.onEvent(ChatThreadEvent.ReplyCleared)
            runCurrent()
            assertEquals(original.text, f.draftSlot.value?.text)
            assertNull(f.draftSlot.value?.replyToEventId)
        }
    }

    private suspend fun TestScope.choose(vm: ChatThreadViewModel, key: String) {
        vm.onEvent(ChatThreadEvent.MessageSelected(key))
        runCurrent()
        assertTrue(vm.state.value.canReplySelection)
        vm.onEvent(ChatThreadEvent.ReplySelected)
    }

    private suspend fun TestScope.withThread(
        block:
            suspend TestScope.(
                ChatThreadFixture,
                ChatThreadViewModel,
                ViewModelStore,
                MutableList<ChatThreadEffect>,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ChatThreadFixture()
        val vm = f.model()
        val store = ViewModelStore().also { it.put("thread", vm) }
        val effects = mutableListOf<ChatThreadEffect>()
        val collection = backgroundScope.launch { vm.effects.collect { effects += it } }
        try {
            block(f, vm, store, effects)
        } finally {
            store.clear()
            collection.cancel()
            runCurrent()
            assertEquals(0, f.activeQuotes)
            Dispatchers.resetMain()
        }
    }
}
