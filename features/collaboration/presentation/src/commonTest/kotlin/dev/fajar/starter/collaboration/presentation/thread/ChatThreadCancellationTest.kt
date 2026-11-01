@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ChatThreadCancellationTest {
    @Test
    fun accountLossClearsASelectionEvenWhenItsCommittedReplyIsUncooperative() = runTest {
        withThread { f, vm, _ ->
            val original = f.queued()
            val release = CompletableDeferred<Unit>()
            f.afterCancel = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.CancelRequested(original.intent.id))
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            runCurrent()
            f.base.session.value = null
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertNull(vm.state.value.cancellationDialog)
            assertTrue(vm.state.value.rows.isEmpty())
            assertNotNull(vm.state.value.localFailure)
            assertNotNull(f.cancelled[original.intent.id])
        }
    }

    @Test
    fun aLateDismissedReplyCannotCloseAnotherMessagesDialog() = runTest {
        withThread { f, vm, _ ->
            val original = f.queued()
            val other =
                original.copy(
                    intent = original.intent.copy(id = chatId(84), text = "Other message")
                )
            f.records.value += other
            val release = CompletableDeferred<Unit>()
            f.afterCancel = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.CancelRequested(original.intent.id))
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            runCurrent()
            vm.onEvent(ChatThreadEvent.CancellationDismissed)
            vm.onEvent(ChatThreadEvent.CancelRequested(other.intent.id))
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(other.intent.text, vm.state.value.cancellationDialog?.text)
            assertFalse(vm.state.value.cancellationDialog!!.busy)
            assertEquals(listOf(other), f.records.value)
            assertEquals(listOf(original), f.cancellations)
        }
    }

    @Test
    fun requestingAndDismissingDoesNotMutateTheOriginalOrCurrentDraft() = runTest {
        withThread { f, vm, _ ->
            val original = f.queued()
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("New draft"))
            advanceTimeBy(250)
            runCurrent()
            vm.onEvent(ChatThreadEvent.CancelRequested(original.intent.id))
            runCurrent()
            assertEquals(original.intent.text, vm.state.value.cancellationDialog?.text)
            assertFalse(vm.state.value.canSend)
            vm.onEvent(ChatThreadEvent.CancellationDismissed)
            runCurrent()
            assertNull(vm.state.value.cancellationDialog)
            assertEquals("New draft", vm.state.value.draft)
            assertTrue(f.cancellations.isEmpty())
            assertEquals(listOf(original), f.records.value)
        }
    }

    @Test
    fun duplicateConfirmationCommitsOnlyTheExactSelectedMessage() = runTest {
        withThread { f, vm, _ ->
            val original = f.queued()
            val other =
                original.copy(
                    intent = original.intent.copy(id = chatId(82), text = "Independent message")
                )
            f.records.value += other
            val release = CompletableDeferred<Unit>()
            f.beforeCancel = { release.await() }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.CancelRequested(original.intent.id))
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            runCurrent()
            assertTrue(vm.state.value.cancellationDialog!!.busy)
            assertEquals(listOf(original), f.cancellations)
            release.complete(Unit)
            runCurrent()
            assertNull(vm.state.value.cancellationDialog)
            assertEquals(listOf(other), f.records.value)
            assertEquals(listOf(other.intent.id), vm.state.value.rows.map { it.localId })
            assertEquals(
                CollaborationMessageState.Cancelled,
                f.cancelled[original.intent.id]?.state,
            )
        }
    }

    @Test
    fun lostCommitReplyRetriesTheOriginalEvenAfterItsPendingRowDisappears() = runTest {
        withThread { f, vm, _ ->
            val original = f.queued()
            f.cancelFailure = f.base.network
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.CancelRequested(original.intent.id))
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            runCurrent()
            assertTrue(f.records.value.isEmpty())
            assertEquals(f.base.network, vm.state.value.cancellationDialog?.failure)
            assertFalse(vm.state.value.cancellationDialog!!.unavailable)
            val independent =
                original.copy(
                    intent = original.intent.copy(id = chatId(83), text = "Newer pending")
                )
            f.records.value = listOf(independent)
            runCurrent()
            assertFalse(vm.state.value.cancellationDialog!!.unavailable)
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            runCurrent()
            assertNull(vm.state.value.cancellationDialog)
            assertEquals(listOf(original, original), f.cancellations)
            assertEquals(listOf(independent), f.records.value)
        }
    }

    @Test
    fun changedPendingStateMakesAnOpenSelectionUnavailableWithoutReplacingIt() = runTest {
        withThread { f, vm, _ ->
            val original = f.queued()
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.CancelRequested(original.intent.id))
            runCurrent()
            val started =
                original.copy(
                    revision = 1,
                    state = CollaborationMessageState.Staging(f.source, emptySet()),
                )
            f.records.value = listOf(started)
            runCurrent()
            assertTrue(vm.state.value.cancellationDialog!!.unavailable)
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            runCurrent()
            assertTrue(f.cancellations.isEmpty())
            assertEquals(listOf(started), f.records.value)
        }
    }

    @Test
    fun stagingWinningAfterConfirmationNeverReportsCancellationSuccess() = runTest {
        withThread { f, vm, _ ->
            val original = f.queued()
            f.rejectCancel = true
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.CancelRequested(original.intent.id))
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            runCurrent()
            assertTrue(vm.state.value.cancellationDialog!!.unavailable)
            assertEquals(
                "client.collaboration_cancellation_unavailable",
                vm.state.value.cancellationDialog?.failure?.code,
            )
            assertEquals(listOf(original), f.records.value)
        }
    }

    @Test
    fun deadlineRetainsTheSelectedOriginalForExplicitRetry() = runTest {
        withThread { f, vm, _ ->
            val original = f.queued()
            f.beforeCancel = { awaitCancellation() }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.CancelRequested(original.intent.id))
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(
                "client.collaboration_cancellation_pending",
                vm.state.value.cancellationDialog?.failure?.code,
            )
            assertEquals(listOf(original), f.records.value)
            f.beforeCancel = {}
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            runCurrent()
            assertNull(vm.state.value.cancellationDialog)
            assertEquals(listOf(original, original), f.cancellations)
        }
    }

    @Test
    fun dismissalOrStopCancelsTheWaiterAndClearsItsPrivateSelection() = runTest {
        for (event in
            listOf(
                ChatThreadEvent.CancellationDismissed,
                ChatThreadEvent.Stopped,
                ChatThreadEvent.BackRequested,
            )) {
            withThread { f, vm, _ ->
                val original = f.queued()
                var closed = false
                f.beforeCancel = {
                    try {
                        awaitCancellation()
                    } finally {
                        closed = true
                    }
                }
                vm.onEvent(ChatThreadEvent.Entered)
                runCurrent()
                vm.onEvent(ChatThreadEvent.CancelRequested(original.intent.id))
                vm.onEvent(ChatThreadEvent.CancellationConfirmed)
                runCurrent()
                vm.onEvent(event)
                runCurrent()
                assertTrue(closed)
                assertNull(vm.state.value.cancellationDialog)
                assertEquals(listOf(original), f.records.value)
            }
        }
    }

    @Test
    fun disposalAfterCommitKeepsTheArchiveButNeverPublishesALateDialog() = runTest {
        withThread { f, vm, store ->
            val original = f.queued()
            val release = CompletableDeferred<Unit>()
            f.afterCancel = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.CancelRequested(original.intent.id))
            vm.onEvent(ChatThreadEvent.CancellationConfirmed)
            runCurrent()
            assertNotNull(f.cancelled[original.intent.id])
            store.clear()
            release.complete(Unit)
            runCurrent()
            assertNull(vm.state.value.cancellationDialog)
            assertTrue(f.records.value.isEmpty())
        }
    }

    private fun ChatThreadFixture.queued(): CollaborationMessageRecord =
        CollaborationMessageRecord(
                CollaborationMessageIntent(
                    chatId(81),
                    setup.owner.let {
                        CollaborationMessageOwner(it.audience, it.accountId, it.companyId)
                    },
                    conversationId,
                    base.clock.now(),
                    "Original queued message",
                ),
                0,
                CollaborationMessageState.Queued,
            )
            .also { records.value = listOf(it) }

    private suspend fun TestScope.withThread(
        block: suspend TestScope.(ChatThreadFixture, ChatThreadViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ChatThreadFixture()
        val vm = f.model()
        val store = ViewModelStore().also { it.put("thread", vm) }
        try {
            block(f, vm, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
