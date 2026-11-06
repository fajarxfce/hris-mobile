@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ChatThreadEditorTest {
    @Test
    fun offlineEditPreservesTheNormalDraftAndReplyWhileUpdatingOneOriginalBubble() = runTest {
        withEditor { f, vm, effects ->
            vm.onEvent(ChatThreadEvent.DraftChanged("Normal draft"))
            select(vm)
            vm.onEvent(ChatThreadEvent.ReplySelected)
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            val draft = vm.state.value.draft
            val quote = vm.state.value.draftReplyToEventId
            val writes = f.draftWrites.toList()
            val before = vm.state.value.rows.single()
            begin(vm)
            assertEquals("Original", vm.state.value.editing?.text)
            assertFalse(vm.state.value.canSend)
            vm.onEvent(ChatThreadEvent.EditChanged("Edited offline"))
            vm.onEvent(ChatThreadEvent.EditSaveRequested)
            runCurrent()
            val after = vm.state.value.rows.single()
            assertEquals("Edited offline", after.text)
            assertEquals(before.key, after.key)
            assertEquals(before.timestamp, after.timestamp)
            assertEquals(before.reference, after.reference)
            assertEquals(CollaborationMessageDelivery.PENDING, after.editDelivery)
            assertEquals(CollaborationMessageDelivery.SENT, after.delivery)
            assertNull(vm.state.value.editing)
            assertEquals(draft, vm.state.value.draft)
            assertEquals(quote, vm.state.value.draftReplyToEventId)
            assertEquals(writes, f.draftWrites)
            val queued = f.insertions.single()
            assertNotNull(queued.editTarget)
            assertNull(queued.replyToEventId)
            assertTrue(effects.contains(ChatThreadEffect.FocusComposer))
            assertTrue(f.transactions.isEmpty())
        }
    }

    @Test
    fun cancelAndBackRestoreTheDraftWithoutQueueingOrLeavingTheRoom() = runTest {
        withEditor { f, vm, effects ->
            vm.onEvent(ChatThreadEvent.DraftChanged("Kept draft"))
            runCurrent()
            for (cancel in listOf(ChatThreadEvent.EditCancelled, ChatThreadEvent.BackRequested)) {
                begin(vm)
                vm.onEvent(ChatThreadEvent.EditChanged("Discard this edit"))
                vm.onEvent(cancel)
                runCurrent()
                assertNull(vm.state.value.editing)
                assertEquals("Kept draft", vm.state.value.draft)
                assertEquals("Original", vm.state.value.rows.single().text)
                assertTrue(f.insertions.isEmpty())
                assertFalse(effects.contains(ChatThreadEffect.Back))
            }
        }
    }

    @Test
    fun stoppedLostWriteKeepsItsOperationAndRetriesAfterExpiryWithoutAnotherInsert() = runTest {
        withEditor { f, vm, _ ->
            begin(vm)
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            f.afterInsert = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    released.complete(Unit)
                }
            }
            vm.onEvent(ChatThreadEvent.EditChanged("Retained edit"))
            vm.onEvent(ChatThreadEvent.EditSaveRequested)
            runCurrent()
            assertTrue(entered.isCompleted)
            val intent = f.insertions.single()
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            assertTrue(released.isCompleted)
            assertTrue(assertNotNull(vm.state.value.editing).locked)
            f.afterInsert = {}
            f.base.now += 20.minutes
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.EditSaveRequested)
            runCurrent()
            assertNull(vm.state.value.editing)
            assertEquals(listOf(intent), f.insertions)
            assertEquals("Retained edit", vm.state.value.rows.single().text)
        }
    }

    @Test
    fun accountReplacementCancelsPreparationAndCannotPublishAnOldEditor() = runTest {
        withEditor { f, vm, _ ->
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            f.edits.beforeTarget = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    released.complete(Unit)
                }
            }
            begin(vm)
            assertTrue(entered.isCompleted)
            f.base.session.value = null
            runCurrent()
            assertTrue(released.isCompleted)
            assertNull(vm.state.value.editing)
            assertNull(vm.state.value.title)
            assertTrue(vm.state.value.rows.isEmpty())
            assertTrue(f.insertions.isEmpty())
        }
    }

    @Test
    fun unavailableOriginalKeepsTheEditBufferAndOrdinaryDraftWithoutPretendingItWasQueued() =
        runTest {
            withEditor { f, vm, _ ->
                vm.onEvent(ChatThreadEvent.DraftChanged("Normal draft"))
                runCurrent()
                begin(vm)
                vm.onEvent(ChatThreadEvent.EditChanged("Still available to copy"))
                runCurrent()
                f.edits.unavailable = true
                vm.onEvent(ChatThreadEvent.EditSaveRequested)
                runCurrent()
                val editor = assertNotNull(vm.state.value.editing)
                assertEquals("Still available to copy", editor.text)
                assertNotNull(editor.failure)
                assertFalse(editor.busy)
                assertTrue(f.insertions.isEmpty())
                assertEquals("Original", vm.state.value.rows.single().text)
                assertEquals("Normal draft", vm.state.value.draft)
            }
        }

    private fun TestScope.select(vm: ChatThreadViewModel) {
        vm.onEvent(ChatThreadEvent.MessageSelected(vm.state.value.rows.single().key))
        runCurrent()
    }

    @Test
    fun rejectedEditCanBeCopiedAfterReopeningWithoutChangingDraftOrDiscardingTheCommand() =
        runTest {
            withEditor { f, vm, effects ->
                vm.onEvent(ChatThreadEvent.DraftChanged("Normal draft"))
                runCurrent()
                begin(vm)
                vm.onEvent(ChatThreadEvent.EditChanged("Keep my attempted change"))
                vm.onEvent(ChatThreadEvent.EditSaveRequested)
                runCurrent()
                val queued = f.records.value.single()
                val origin = CollaborationMessageOrigin(f.self.matrixUserId, "b".repeat(64))
                f.edits.facts[queued.intent.id] =
                    listOf(
                        CollaborationEditEvidence(
                            "\$rejected-edit",
                            origin.sender,
                            queued.intent.editTarget!!.eventId,
                            f.base.now.toEpochMilliseconds(),
                            origin.contentHash,
                            queued.intent.id,
                            eligible = false,
                            redacted = false,
                        )
                    )
                f.records.value =
                    listOf(
                        queued.copy(
                            revision = 1,
                            state = CollaborationMessageState.Staged(f.source, "tx-edit", origin),
                        )
                    )
                runCurrent()
                assertEquals("Original", vm.state.value.rows.single().text)
                assertEquals(
                    "Keep my attempted change",
                    vm.state.value.rows.single().failedEditText,
                )
                vm.onEvent(ChatThreadEvent.Stopped)
                runCurrent()
                f.base.now += 20.minutes
                vm.onEvent(ChatThreadEvent.Entered)
                runCurrent()
                select(vm)
                assertTrue(vm.state.value.canCopyFailedEdits)
                vm.onEvent(ChatThreadEvent.CopyFailedEdits)
                runCurrent()
                val copy = effects.filterIsInstance<ChatThreadEffect.CopyMessages>().single()
                assertEquals("Keep my attempted change", copy.text)
                assertTrue(vm.confirmCopy(copy))
                assertFalse(vm.state.value.selecting)
                assertEquals("Normal draft", vm.state.value.draft)
                assertEquals(queued.intent, f.records.value.single().intent)
                assertTrue(f.edits.archived.isEmpty())
                assertTrue(f.transactions.isEmpty())
                f.base.session.value = null
                assertFalse(vm.confirmCopy(copy))
                runCurrent()
                assertTrue(vm.state.value.rows.isEmpty())
            }
        }

    private fun TestScope.begin(vm: ChatThreadViewModel) {
        select(vm)
        assertTrue(vm.state.value.canEditSelection)
        vm.onEvent(ChatThreadEvent.EditSelected)
        runCurrent()
    }

    private suspend fun TestScope.withEditor(
        block:
            suspend TestScope.(
                ChatThreadFixture, ChatThreadViewModel, MutableList<ChatThreadEffect>,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ChatThreadFixture()
        f.base.now = Clock.System.now()
        f.historyRows.value =
            listOf(
                f.original()
                    .copy(
                        sender = f.self.matrixUserId,
                        logicalId = chatId(88),
                        timestamp = f.base.now.toEpochMilliseconds() - 5_000,
                        text = "Original",
                    )
            )
        val vm = f.model()
        val store = ViewModelStore().also { it.put("thread", vm) }
        val effects = mutableListOf<ChatThreadEffect>()
        val collection = backgroundScope.launch { vm.effects.collect { effects += it } }
        try {
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals("Original", vm.state.value.rows.single().text)
            block(f, vm, effects)
        } finally {
            store.clear()
            collection.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
