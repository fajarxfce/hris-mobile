@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ChatThreadViewModelTest {
    @Test
    fun expiredCredentialsKeepLocalHistoryAndAllowOwnedQueueWithoutNativeWork() = runTest {
        withModel { f, vm, _, _ ->
            f.base.now += 30.days
            f.historyRows.value = listOf(f.original())
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals("Colleague 1", vm.state.value.title)
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            assertEquals(0, f.setup.reads)
            assertEquals(0, f.timelineOpens)
            vm.onEvent(ChatThreadEvent.DraftChanged("  offline\nmessage  "))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals("  offline\nmessage  ", f.records.value.single().intent.text)
            assertEquals(CollaborationMessageDelivery.PENDING, vm.state.value.rows.first().delivery)
            assertEquals("", vm.state.value.draft)
            assertEquals(0, f.deliveries)
        }
    }

    @Test
    fun lostLocalReplyRetainsExactIdAndBodyUntilRetryReadsTheCommit() = runTest {
        withModel { f, vm, _, effects ->
            f.insertResult = AppResult.Failed(f.base.network)
            vm.onEvent(ChatThreadEvent.Entered)
            vm.onEvent(ChatThreadEvent.DraftChanged("exact original"))
            runCurrent()
            // Entry is asynchronous; edit after the owned context is ready.
            vm.onEvent(ChatThreadEvent.DraftChanged("exact original"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            val saved = f.records.value.single()
            assertTrue(vm.state.value.submissionPending)
            vm.onEvent(ChatThreadEvent.DraftChanged("replacement"))
            runCurrent()
            assertEquals("exact original", vm.state.value.draft)
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(listOf(saved.intent), f.insertions)
            assertFalse(vm.state.value.submissionPending)
            assertEquals("", vm.state.value.draft)
            assertEquals(listOf<ChatThreadEffect>(ChatThreadEffect.ScrollToNewest), effects)
        }
    }

    @Test
    fun stoppedUncooperativeCommitNeverPublishesSuccessAndRetryReusesIt() = runTest {
        withModel { f, vm, _, effects ->
            val release = CompletableDeferred<Unit>()
            f.afterInsert = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("saved before cancellation"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(1, f.records.value.size)
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.submissionPending)
            assertTrue(effects.isEmpty())
            vm.onEvent(ChatThreadEvent.Entered)
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertFalse(vm.state.value.submissionPending)
            assertEquals(1, f.insertions.size)
        }
    }

    @Test
    fun hiddenOwnerLossClearsBodiesDraftAndPendingIdentityAndDisposalClosesObservers() = runTest {
        withModel { f, vm, store, _ ->
            f.historyRows.value = listOf(f.original())
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("private draft"))
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            f.base.session.value = null
            runCurrent()
            assertNull(vm.state.value.title)
            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals("", vm.state.value.draft)
            assertNotNull(vm.state.value.localFailure)
            store.clear()
            runCurrent()
            assertEquals(0, f.base.session.subscriptionCount.value)
            assertEquals(0, f.records.subscriptionCount.value)
            assertEquals(0, f.historyRows.subscriptionCount.value)
        }
    }

    @Test
    fun foregroundTimelineIsReleasedOnStopAndNewEntryOpensOneChild() = runTest {
        withModel { f, vm, store, _ ->
            f.online()
            vm.onEvent(ChatThreadEvent.Entered)
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals(ChatThreadConnection.CONNECTED, vm.state.value.connection)
            assertEquals(1, f.timelineOpens)
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            assertEquals(1, f.timelineCloses)
            assertEquals(0, f.setup.closes)
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals(2, f.timelineOpens)
            store.clear()
            runCurrent()
            assertEquals(2, f.timelineCloses)
            assertEquals(0, f.updates.subscriptionCount.value)
            assertEquals(0, f.setup.closes)
        }
    }

    @Test
    fun denialDuringLateTimelineAcquisitionClosesChildAndKeepsContentHidden() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            val release = CompletableDeferred<Unit>()
            f.beforeEnter = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals(1, f.timelineOpens)
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(1, f.timelineCloses)
            assertTrue(vm.state.value.rows.isEmpty())
            assertNull(vm.state.value.title)
            assertEquals(f.base.denied, vm.state.value.localFailure)
        }
    }

    @Test
    fun sendStoresAndSchedulesWhileExternalDeliveryUpdatesTheOwnedProjection() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("one send"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(1, f.insertions.size)
            assertEquals(0, f.deliveries)
            assertEquals(CollaborationMessageState.Queued, f.records.value.single().state)
            assertTrue(f.scheduledKeys.isNotEmpty())
            val queued = f.records.value.single()
            f.records.value =
                listOf(
                    queued.copy(
                        state =
                            CollaborationMessageState.Sent(
                                f.source,
                                "tx",
                                "$" + "event",
                                CollaborationMessageOrigin(f.self.matrixUserId, "a".repeat(64)),
                            )
                    )
                )
            runCurrent()
            assertEquals(CollaborationMessageDelivery.SENT, vm.state.value.rows.single().delivery)
            assertTrue(f.historyRows.value.isEmpty())
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            runCurrent()
            assertEquals(0, f.deliveries)
            assertEquals(0, f.queueCloses)
            assertEquals(1, f.records.value.size)
        }
    }

    @Test
    fun roomPreparationIsScheduledForTheWorkerAndReadinessIsObservedSeparately() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.room =
                f.room.copy(
                    state = CollaborationDirectRoomState.NOT_REQUESTED,
                    matrixRoomId = null,
                    version = null,
                )
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertTrue(f.roomRequests.isEmpty())
            assertEquals(ChatThreadConnection.WAITING, vm.state.value.connection)
            vm.onEvent(ChatThreadEvent.DraftChanged("first"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertTrue(f.scheduledKeys.isNotEmpty())
            assertTrue(f.controls.retained.isEmpty())
            assertTrue(f.roomRequests.isEmpty())
            f.room =
                f.room.copy(
                    state = CollaborationDirectRoomState.READY,
                    matrixRoomId = f.source.roomId,
                    version = 1,
                )
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(ChatThreadConnection.CONNECTED, vm.state.value.connection)
            assertEquals(0, f.deliveries)
            assertEquals(CollaborationMessageState.Queued, f.records.value.single().state)
        }
    }

    @Test
    fun failedRoomRetryRetainsExplicitIntentAndWakesWorkerWithoutSendingFromTheScreen() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.room =
                f.room.copy(
                    state = CollaborationDirectRoomState.FAILED,
                    matrixRoomId = null,
                    retryable = true,
                    retryAt = f.base.now.toEpochMilliseconds(),
                )
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("retry after review"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertTrue(f.controls.retained.isEmpty())
            assertTrue(f.roomRequests.isEmpty())
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            runCurrent()
            assertEquals(1, f.controls.retained.size)
            assertTrue(f.roomRequests.isEmpty())
            assertEquals(0, f.deliveries)
            assertTrue(f.scheduledKeys.isNotEmpty())
        }
    }

    @Test
    fun overlappingLocalPagesDeduplicateAndDoNotAskSdkForOlderHistory() = runTest {
        withModel { f, vm, _, _ ->
            f.base.now += 30.days
            f.pageNext = "0000000000001000/m-" + "a".repeat(64)
            f.historyRows.value = listOf(f.original())
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertTrue(vm.state.value.hasOlder)
            vm.onEvent(ChatThreadEvent.OlderRequested)
            runCurrent()
            assertEquals(1, vm.state.value.rows.size)
            assertFalse(vm.state.value.hasOlder)
            assertTrue(f.pageNext in f.requestedPages)
            assertEquals(0, f.historyReads)
            assertEquals(0, f.timelineOpens)
        }
    }

    @Test
    fun validationFailureDoesNotLockComposerOrReserveAnUnsendableIntent() = runTest {
        withModel { f, vm, _, _ ->
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("bad\u0000text"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(FailureKind.Validation, vm.state.value.failure?.kind)
            assertTrue(vm.state.value.canCompose)
            assertFalse(vm.state.value.submissionPending)
            assertTrue(f.insertions.isEmpty())
            vm.onEvent(ChatThreadEvent.DraftChanged("corrected"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals("corrected", f.records.value.single().intent.text)
        }
    }

    @Test
    fun completedTimelineClosesChildAndExposesReconnectWithoutSpinning() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.timelineFlow = kotlinx.coroutines.flow.flowOf(AppResult.Success(emptyList()))
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals(ChatThreadConnection.OFFLINE, vm.state.value.connection)
            assertEquals("client.collaboration_timeline_stopped", vm.state.value.failure?.code)
            assertEquals(1, f.timelineOpens)
            assertEquals(1, f.timelineCloses)
            assertEquals(0, f.setup.closes)
        }
    }

    @Test
    fun stoppingKeepsDurableWorkAndReentryDoesNotCreateAnotherSend() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("continue after closing"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            val stored = f.records.value.single()
            val keys = f.scheduledKeys.toList()
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            assertEquals(listOf(stored), f.records.value)
            assertEquals(keys, f.scheduledKeys)
            assertEquals(1, f.timelineCloses)
            assertEquals(ChatThreadConnection.OFFLINE, vm.state.value.connection)
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals(0, f.deliveries)
            assertEquals(1, f.insertions.size)
            assertEquals(listOf(stored), f.records.value)
        }
    }

    @Test
    fun failedRecoveryKeepsCompanyDenialAndAdmittedRefreshRestartsLocalHistory() = runTest {
        withModel { f, vm, _, _ ->
            f.historyRows.value = listOf(f.original())
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            f.setup.read = { AppResult.Failed(f.base.network) }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals(f.base.denied, vm.state.value.localFailure)
            f.setup.read = { AppResult.Success(null) }
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            runCurrent()
            assertNull(vm.state.value.localFailure)
            assertNull(f.base.gate.value.denial)
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
        }
    }

    @Test
    fun explicitRefreshReopensFullHistoryAndReacquiresAChildWithoutSending() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.historyRows.value = listOf(f.original())
            f.historyFull = true
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            advanceTimeBy(400)
            runCurrent()
            assertEquals(
                "client.collaboration_history_focus_capacity",
                vm.state.value.failure?.code,
            )
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            runCurrent()
            advanceTimeBy(400)
            runCurrent()
            assertEquals(1, f.historyReopens)
            assertEquals(2, f.timelineOpens)
            assertEquals(1, f.timelineCloses)
            assertEquals(ChatThreadConnection.CONNECTED, vm.state.value.connection)
            assertNull(vm.state.value.failure)
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            assertEquals(0, f.deliveries)
        }
    }

    @Test
    fun stoppingDuringLateCapacityRecoveryCannotRestoreOrOpenAnotherChild() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.historyFull = true
            val release = CompletableDeferred<Unit>()
            f.beforeHistoryReopen = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            advanceTimeBy(400)
            runCurrent()
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            runCurrent()
            assertEquals(1, f.historyReopens)
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(0, f.setup.restores)
            assertEquals(1, f.timelineOpens)
            assertEquals(1, f.timelineCloses)
            assertEquals(ChatThreadConnection.OFFLINE, vm.state.value.connection)
            assertEquals(0, f.deliveries)
        }
    }

    private suspend fun TestScope.withModel(
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
            Dispatchers.resetMain()
        }
    }
}
