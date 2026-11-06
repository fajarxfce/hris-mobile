@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ChatThreadReadTest {
    @Test
    fun offlineMarkWaitsForAuthenticatedRoomBindingInTheRetainedHistoryPage() = runTest {
        withModel { f, vm, _ ->
            val seen = f.readState.original()
            f.historyPages[null to null] =
                AppResult.Success(CollaborationHistoryPage(listOf(seen), null))
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.show(seen)
            runCurrent()
            assertTrue(f.readState.marks.isEmpty())
            f.historyPages[null to null] =
                AppResult.Success(
                    CollaborationHistoryPage(listOf(seen), null, roomId = f.source.roomId)
                )
            f.readState.original(2) // A later storage emission supplies the authenticated binding.
            runCurrent()
            assertEquals(setOf(seen.primaryEventId), f.readState.state.readEventIds)
            assertEquals(1, f.readState.marks.size)
        }
    }

    @Test
    fun offlineReadRequiresCurrentViewportAndMarksOnlyExactVisibleOriginals() = runTest {
        withModel { f, vm, _ ->
            f.base.now += 365.days
            val seen = f.readState.original()
            val unseen = f.readState.original(2)
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertTrue(f.readState.marks.isEmpty())
            vm.onEvent(
                ChatThreadEvent.ViewportChanged(
                    "previous-owner",
                    vm.state.value.rows.map { it.key },
                )
            )
            runCurrent()
            assertTrue(f.readState.marks.isEmpty())
            vm.show(seen)
            runCurrent()
            assertEquals(setOf(seen.primaryEventId), f.readState.state.readEventIds)
            assertFalse(unseen.primaryEventId in f.readState.state.readEventIds)
            assertEquals(
                CollaborationReadReceiptVisibility.Private,
                f.readState.marks.single().visibility,
            )
            assertTrue(CollaborationRecoveryWake.WORK_KEY in f.scheduledKeys)
            assertEquals(0, f.timelineOpens)
            assertEquals(0, f.deliveries)
            vm.onEvent(ChatThreadEvent.Stopped)
            vm.show(unseen)
            runCurrent()
            assertEquals(1, f.readState.marks.size)
        }
    }

    @Test
    fun selfAndForgedRowsCannotMarkAndVisibleProjectionDoesNotInventCoverage() = runTest {
        withModel { f, vm, _ ->
            val own = f.readState.original(1, f.self.matrixUserId)
            val peer = f.readState.original(2)
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(
                ChatThreadEvent.ViewportChanged(
                    vm.state.value.readViewportToken,
                    listOf("event:forged"),
                )
            )
            vm.show(own)
            runCurrent()
            assertTrue(f.readState.marks.isEmpty())
            // A shown SDK-only event still needs an acquired original before a read mark.
            f.past.facts.remove(peer.primaryEventId)
            vm.show(peer)
            runCurrent()
            assertTrue(f.readState.marks.isEmpty())
            assertTrue(f.readState.state.readEventIds.isEmpty())
        }
    }

    @Test
    fun repeatedViewportAndRecreatedViewModelReuseRetainedCoverageAndPendingIntent() = runTest {
        withModel { f, vm, store ->
            val seen = f.readState.original()
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            repeat(5) { vm.show(seen) }
            runCurrent()
            val intent = f.readState.marks.single()
            val reads = f.readState.reads
            repeat(5) { vm.show(seen) }
            runCurrent()
            assertEquals(reads, f.readState.reads)
            store.clear()
            runCurrent()
            val next = f.model()
            store.put("replacement", next)
            next.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            next.show(seen)
            runCurrent()
            assertEquals(listOf(intent), f.readState.marks)
            assertEquals(listOf(intent), f.readState.state.pending)
            assertTrue(f.readState.wakeCount >= 2)
        }
    }

    @Test
    fun uncertainLocalCommitRecoversSameIntentOnExplicitRefresh() = runTest {
        withModel { f, vm, _ ->
            val seen = f.readState.original()
            f.readState.markFailure = f.base.network
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.show(seen)
            runCurrent()
            val intent = f.readState.marks.single()
            assertEquals(0, f.readState.wakeCount)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, f.readState.marks.size)
            f.readState.markFailure = null
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            runCurrent()
            assertEquals(listOf(intent), f.readState.marks)
            assertEquals(listOf(intent), f.readState.state.pending)
            assertTrue(f.readState.wakeCount > 0)
        }
    }

    @Test
    fun stoppedCommitIsJoinedBeforeResumedViewportAndNeverDuplicatesTheIntent() = runTest {
        withModel { f, vm, _ ->
            val first = f.readState.original()
            val next = f.readState.original(2)
            val release = CompletableDeferred<Unit>()
            f.readState.afterMark = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.show(first)
            runCurrent()
            val initial = f.readState.marks.single()
            vm.onEvent(ChatThreadEvent.Stopped)
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.show(next)
            runCurrent()
            assertEquals(1, f.readState.activeMarks)
            assertEquals(1, f.readState.marks.size)
            assertEquals(0, f.readState.wakeCount)
            f.readState.afterMark = {}
            release.complete(Unit)
            runCurrent()
            assertEquals(1, f.readState.maxActiveMarks)
            assertEquals(0, f.readState.activeMarks)
            assertEquals(2, f.readState.marks.size)
            assertEquals(initial, f.readState.marks.first())
            assertEquals(
                setOf(first.primaryEventId, next.primaryEventId),
                f.readState.state.readEventIds,
            )
        }
    }

    @Test
    fun focusLossCancelsPendingReadAndReentryCanRetryWithoutPolling() = runTest {
        withModel { f, vm, _ ->
            val seen = f.readState.original()
            var cancelled = false
            f.readState.beforeRead = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.show(seen)
            runCurrent()
            vm.onEvent(
                ChatThreadEvent.ViewportChanged(vm.state.value.readViewportToken, emptyList())
            )
            runCurrent()
            assertTrue(cancelled)
            assertTrue(f.readState.marks.isEmpty())
            f.readState.beforeRead = {}
            vm.show(seen)
            runCurrent()
            assertEquals(1, f.readState.marks.size)
        }
    }

    @Test
    fun confirmedDenialDuringUncooperativeCommitKeepsProjectionClosedAndFactsRetained() = runTest {
        withModel { f, vm, _ ->
            val seen = f.readState.original()
            val release = CompletableDeferred<Unit>()
            f.readState.afterMark = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            val token = vm.state.value.readViewportToken
            vm.show(seen)
            runCurrent()
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals(f.base.denied, vm.state.value.localFailure)
            assertNull(vm.state.value.readViewportToken)
            assertEquals(setOf(seen.primaryEventId), f.readState.state.readEventIds)
            assertEquals(1, f.readState.state.pending.size)
            assertEquals(0, f.readState.wakeCount)
            vm.onEvent(
                ChatThreadEvent.ViewportChanged(token, listOf("event:${seen.primaryEventId}"))
            )
            runCurrent()
            assertEquals(1, f.readState.marks.size)
        }
    }

    private fun ChatThreadViewModel.show(message: CollaborationHistoryMessage) {
        val row = state.value.rows.single { it.eventId == message.primaryEventId }
        onEvent(ChatThreadEvent.ViewportChanged(state.value.readViewportToken, listOf(row.key)))
    }

    private suspend fun TestScope.withModel(
        block: suspend TestScope.(ChatThreadFixture, ChatThreadViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ChatThreadFixture()
        val model = fixture.model()
        val store = ViewModelStore().also { it.put("thread", model) }
        try {
            block(fixture, model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
