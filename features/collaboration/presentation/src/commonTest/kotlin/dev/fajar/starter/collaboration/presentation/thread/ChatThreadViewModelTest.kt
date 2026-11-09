@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ChatThreadViewModelTest {
    @Test
    fun sendsAppearBeforeLocalCommitAndTypingContinuesAcrossConsecutiveGestures() = runTest {
        withModel { f, vm, _, _ ->
            val release = CompletableDeferred<Unit>()
            f.beforeComposerHandoff = { release.await() }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("A"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertTrue(vm.state.value.canCompose)
            assertFalse(vm.state.value.saving)
            assertEquals("", vm.state.value.draft)
            assertEquals("A", vm.state.value.rows.single().text)
            assertTrue(f.composerWrites.isEmpty())
            vm.onEvent(ChatThreadEvent.DraftChanged("B"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Keep C"))
            runCurrent()
            assertEquals(listOf("B", "A"), vm.state.value.rows.map { it.text })
            val keys = vm.state.value.rows.map { it.key }
            release.complete(Unit)
            advanceUntilIdle()
            assertNull(vm.state.value.localFailure, "Local access failure")
            assertNull(vm.state.value.draftFailure?.code, "Composer failure")
            assertEquals(listOf("A", "B"), f.composerWrites.map { it.original.text })
            assertEquals("Keep C", vm.state.value.draft)
            assertEquals("Keep C", f.draftSlot.value?.text)
            assertEquals(keys, vm.state.value.rows.map { it.key })
            assertEquals(0, f.deliveries)
        }
    }

    @Test
    fun retainedOriginalRestoresAfterViewModelReplacementAndCanBeCancelledBeforeCommandCreation() =
        runTest {
            withModel { f, vm, store, _ ->
                vm.onEvent(ChatThreadEvent.Entered())
                runCurrent()
                vm.onEvent(ChatThreadEvent.DraftChanged("Original"))
                vm.onEvent(ChatThreadEvent.SendRequested)
                advanceUntilIdle()
                val original = f.composerWrites.single()
                store.clear()
                runCurrent()
                val restored = f.model()
                store.put("restored", restored)
                restored.onEvent(ChatThreadEvent.Entered())
                advanceUntilIdle()
                assertEquals(original.id, restored.state.value.rows.single().localId)
                restored.onEvent(ChatThreadEvent.CancelRequested(original.id))
                runCurrent()
                restored.onEvent(ChatThreadEvent.CancellationConfirmed)
                advanceUntilIdle()
                assertTrue(restored.state.value.rows.isEmpty())
                assertTrue(f.composerEntries.value.isEmpty())
                assertEquals(CollaborationMessageState.Cancelled, f.cancelled[original.id]?.state)
                assertEquals(1, f.composerWrites.size)
            }
        }

    @Test
    fun coldOutboxOriginalRemainsVisibleUntilTheOrdinaryCommandProjectionArrives() = runTest {
        withModel { f, vm, store, _ ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Retained original"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            advanceUntilIdle()
            val original = f.composerWrites.single()
            store.clear()
            runCurrent()
            val restored = f.model()
            store.put("restored", restored)
            restored.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            val key = restored.state.value.rows.single().key
            val release = CompletableDeferred<Unit>()
            f.beforeCommandProjection = { release.await() }
            f.publishQueuedComposer(original)
            runCurrent()
            assertTrue(f.composerEntries.value.isEmpty())
            assertEquals(key, restored.state.value.rows.single().key)
            assertEquals("Retained original", restored.state.value.rows.single().text)
            assertEquals(
                CollaborationMessageDelivery.PENDING,
                restored.state.value.rows.single().delivery,
            )
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(key, restored.state.value.rows.single().key)
            assertNull(restored.state.value.localFailure)
        }
    }

    @Test
    fun explicitRetryOfColdHeldOriginalCreatesOnlyItsExactCommandAndKeepsNewTyping() = runTest {
        withModel { f, vm, store, _ ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Retry original"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            advanceUntilIdle()
            val original = f.composerWrites.single()
            store.clear()
            runCurrent()
            f.composerEntries.value = listOf(CollaborationComposerOutboxEntry(original, true))
            val restored = f.model()
            store.put("restored", restored)
            restored.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            assertEquals(
                CollaborationMessageDelivery.FAILED,
                restored.state.value.rows.single().delivery,
            )
            restored.onEvent(ChatThreadEvent.DraftChanged("Keep this draft"))
            restored.onEvent(ChatThreadEvent.MessageRetryRequested(original.id))
            advanceUntilIdle()
            assertEquals("Keep this draft", restored.state.value.draft)
            assertEquals(original.id, f.insertions.single().id)
            assertEquals(original.original.text, f.insertions.single().text)
            assertEquals(original.submittedAt, f.insertions.single().createdAt)
            assertEquals(
                CollaborationMessageDelivery.PENDING,
                restored.state.value.rows.single().delivery,
            )
            assertTrue(f.composerEntries.value.isEmpty())
            restored.onEvent(ChatThreadEvent.MessageRetryRequested(original.id))
            advanceUntilIdle()
            assertEquals(1, f.insertions.size)
        }
    }

    @Test
    fun navigationHintStartsGuardedHistoryBeforeWorkspaceObservationAndKeepsTheSameReader() =
        runTest {
            withModel { f, vm, _, _ ->
                f.base.now += 365.days
                f.historyRows.value = listOf(f.original())
                val observed = CompletableDeferred<Unit>()
                f.base.beforeWorkspaceObservation = { observed.await() }
                vm.onEvent(ChatThreadEvent.Entered(f.setup.owner.localListAccess()))
                runCurrent()
                assertFalse(observed.isCompleted)
                assertEquals("Saved conversation", vm.state.value.rows.single().text)
                assertFalse(vm.state.value.loading)
                assertEquals(0, f.timelineOpens)
                val reads = f.requestedWindows.toList()
                assertEquals(1, reads.size)
                observed.complete(Unit)
                runCurrent()
                assertEquals(reads, f.requestedWindows)
                f.base.session.value = null
                runCurrent()
                assertTrue(vm.state.value.rows.isEmpty())
                assertNull(vm.state.value.title)
                assertEquals(0, f.activeHistory)
            }
        }

    @Test
    fun anotherAccountHintCannotReadHistoryBeforeTheObservedOwnerArrives() = runTest {
        withModel { f, vm, _, _ ->
            f.historyRows.value = listOf(f.original())
            f.base.beforeWorkspaceObservation = { awaitCancellation() }
            val foreign = f.setup.owner.localListAccess().copy(accountId = chatId(77))
            vm.onEvent(ChatThreadEvent.Entered(foreign))
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertNull(vm.state.value.title)
            assertNotNull(vm.state.value.localFailure)
            assertTrue(f.requestedWindows.isEmpty())
            assertEquals(0, f.timelineOpens)
        }
    }

    @Test
    fun navigationHintCannotPublishHistoryWhenTheCurrentWorkspaceIsGone() = runTest {
        withModel { f, vm, _, _ ->
            f.historyRows.value = listOf(f.original())
            f.base.beforeWorkspaceObservation = { awaitCancellation() }
            f.base.workspace.value = null
            vm.onEvent(ChatThreadEvent.Entered(f.setup.owner.localListAccess()))
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertNull(vm.state.value.title)
            assertNotNull(vm.state.value.localFailure)
            assertFalse(vm.state.value.canCompose)
            assertEquals(0, f.timelineOpens)
        }
    }

    @Test
    fun navigationHintKeepsRetainedDenialClosedWhileWorkspaceObservationIsPending() = runTest {
        withModel { f, vm, _, _ ->
            f.historyRows.value = listOf(f.original())
            f.base.beforeWorkspaceObservation = { awaitCancellation() }
            f.base.gate.value = CollaborationListAdmission(null, f.base.denied)
            vm.onEvent(ChatThreadEvent.Entered(f.setup.owner.localListAccess()))
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals(f.base.denied, vm.state.value.localFailure)
            assertTrue(f.requestedWindows.isEmpty())
            assertEquals(0, f.timelineOpens)
        }
    }

    @Test
    fun cachedMessagesRenderBeforePendingDraftReadAndWithoutAConnection() = runTest {
        withModel { f, vm, _, _ ->
            f.base.now += 365.days
            f.historyRows.value = listOf(f.original())
            val entered = CompletableDeferred<Unit>()
            f.beforeDraft = {
                assertEquals("Saved conversation", vm.state.value.rows.single().text)
                assertFalse(vm.state.value.loading)
                entered.complete(Unit)
                awaitCancellation()
            }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertTrue(entered.isCompleted)
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            assertFalse(vm.state.value.draftReady)
            assertEquals(0, f.timelineOpens)
        }
    }

    @Test
    fun rejectedSdkKeepsLocalHistoryAndOffersSetupWithoutRepeatedConnectionAttempts() = runTest {
        withModel { f, vm, _, effects ->
            f.online()
            f.setup.opened = false
            f.setup.restore = {
                AppResult.Failed(
                    Failure(
                        FailureKind.Unauthorized,
                        "Rejected",
                        code = "client.collaboration_sdk_unauthorized",
                    )
                )
            }
            f.historyRows.value = listOf(f.original())
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            assertFalse(vm.state.value.loading)
            assertNull(vm.state.value.localFailure)
            assertEquals(ChatThreadConnection.SETUP_REQUIRED, vm.state.value.connection)
            assertEquals(1, f.setup.restores)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, f.setup.restores)
            assertEquals(0, f.timelineOpens)
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            vm.onEvent(ChatThreadEvent.SetupRequested)
            runCurrent()
            assertTrue(effects.contains(ChatThreadEffect.Setup))
        }
    }

    @Test
    fun reopeningAnExhaustedRoomRestoresItsEndWithoutAnotherOlderRequest() = runTest {
        withModel { f, vm, store, _ ->
            f.online()
            f.past.pages += listOf(f.past.event(1))
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertFalse(vm.state.value.hasOlder)
            store.clear()
            runCurrent()
            val restored = f.model()
            store.put("restored", restored)
            restored.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            assertEquals("Older message 1", restored.state.value.rows.single().text)
            assertFalse(restored.state.value.hasOlder)
            repeat(4) { restored.onEvent(ChatThreadEvent.OlderRequested) }
            advanceUntilIdle()
            assertEquals(listOf<String?>(null), f.past.requestedPositions)
            assertEquals(1, f.past.stages)
        }
    }

    @Test
    fun anEmptyCommittedEndDoesNotAdvertiseOlderMessagesAfterRestart() = runTest {
        withModel { f, vm, store, _ ->
            f.online()
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertFalse(vm.state.value.hasOlder)
            store.clear()
            runCurrent()
            val restored = f.model()
            store.put("restored", restored)
            restored.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            assertFalse(restored.state.value.hasOlder)
            assertFalse(restored.state.value.loading)
            restored.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertEquals(1, f.past.requestedPositions.size)
        }
    }

    @Test
    fun expiredCredentialsKeepLocalHistoryAndAllowOwnedQueueWithoutNativeWork() = runTest {
        withModel { f, vm, _, _ ->
            f.base.now += 30.days
            f.historyRows.value = listOf(f.original())
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertEquals("Colleague 1", vm.state.value.title)
            assertEquals(f.setup.owner, vm.state.value.notificationOwner)
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            assertEquals(0, f.setup.reads)
            assertEquals(0, f.timelineOpens)
            vm.onEvent(ChatThreadEvent.DraftChanged("  offline\nmessage  "))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals("  offline\nmessage  ", f.composerWrites.single().original.text)
            assertEquals(CollaborationMessageDelivery.PENDING, vm.state.value.rows.first().delivery)
            assertEquals("", vm.state.value.draft)
            assertEquals(0, f.deliveries)
        }
    }

    @Test
    fun lostLocalReplyKeepsTheCapturedOriginalAndAllowsNewerTypingDuringExactRetry() = runTest {
        withModel { f, vm, _, _ ->
            f.composerFailure = f.base.network
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("exact original"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            val saved = f.composerWrites.single()
            assertFalse(vm.state.value.submissionPending)
            assertTrue(vm.state.value.canCompose)
            vm.onEvent(ChatThreadEvent.DraftChanged("replacement"))
            runCurrent()
            assertEquals("replacement", vm.state.value.draft)
            f.composerFailure = null
            vm.onEvent(ChatThreadEvent.DraftRetryRequested)
            advanceUntilIdle()
            assertEquals(listOf(saved), f.composerWrites)
            assertEquals("replacement", f.draftSlot.value?.text)
            assertTrue(f.insertions.isEmpty())
        }
    }

    @Test
    fun aLocalCommitContinuesWhenTheRoomHidesWithoutAnotherGestureOrNavigationEffect() = runTest {
        withModel { f, vm, _, effects ->
            val release = CompletableDeferred<Unit>()
            f.afterComposerHandoff = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("saved before closing"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(1, f.composerWrites.size)
            vm.onEvent(ChatThreadEvent.DraftChanged("Keep newer text"))
            runCurrent()
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            val before = effects.toList()
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(before, effects)
            assertEquals("Keep newer text", f.draftSlot.value?.text)
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertFalse(vm.state.value.submissionPending)
            assertEquals(1, f.composerWrites.size)
        }
    }

    @Test
    fun hiddenOwnerLossClearsBodiesDraftAndPendingIdentityAndDisposalClosesObservers() = runTest {
        withModel { f, vm, store, _ ->
            f.historyRows.value = listOf(f.original())
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("private draft"))
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            f.base.session.value = null
            runCurrent()
            assertNull(vm.state.value.title)
            assertNull(vm.state.value.notificationOwner)
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
            vm.onEvent(ChatThreadEvent.Entered())
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertEquals(ChatThreadConnection.CONNECTED, vm.state.value.connection)
            assertEquals(1, f.timelineOpens)
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            assertEquals(1, f.timelineCloses)
            assertEquals(0, f.setup.closes)
            vm.onEvent(ChatThreadEvent.Entered())
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
            vm.onEvent(ChatThreadEvent.Entered())
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
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("one send"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(1, f.composerWrites.size)
            assertTrue(f.insertions.isEmpty())
            f.publishQueuedComposer()
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
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertTrue(f.roomRequests.isEmpty())
            assertEquals(ChatThreadConnection.WAITING, vm.state.value.connection)
            vm.onEvent(ChatThreadEvent.DraftChanged("first"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(1, f.composerWrites.size)
            assertTrue(f.insertions.isEmpty())
            f.publishQueuedComposer()
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
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("retry after review"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(1, f.composerWrites.size)
            assertTrue(f.insertions.isEmpty())
            f.publishQueuedComposer()
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
            vm.onEvent(ChatThreadEvent.Entered())
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
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("bad\u0000text"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(FailureKind.Validation, vm.state.value.draftFailure?.kind)
            assertTrue(vm.state.value.canCompose)
            assertFalse(vm.state.value.submissionPending)
            assertTrue(f.insertions.isEmpty())
            vm.onEvent(ChatThreadEvent.DraftChanged("corrected"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals("corrected", f.composerWrites.single().original.text)
        }
    }

    @Test
    fun completedTimelineClosesChildAndExposesReconnectWithoutSpinning() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.timelineFlow = kotlinx.coroutines.flow.flowOf(AppResult.Success(emptyList()))
            vm.onEvent(ChatThreadEvent.Entered())
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
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("continue after closing"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(1, f.composerWrites.size)
            assertTrue(f.insertions.isEmpty())
            f.publishQueuedComposer()
            runCurrent()
            val stored = f.records.value.single()
            val keys = f.scheduledKeys.toList()
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            assertEquals(listOf(stored), f.records.value)
            assertEquals(keys, f.scheduledKeys)
            assertEquals(1, f.timelineCloses)
            assertEquals(ChatThreadConnection.OFFLINE, vm.state.value.connection)
            vm.onEvent(ChatThreadEvent.Entered())
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
            vm.onEvent(ChatThreadEvent.Entered())
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
            vm.onEvent(ChatThreadEvent.Entered())
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
            vm.onEvent(ChatThreadEvent.Entered())
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

    @Test
    fun olderRequestFetchesOneProviderPageAcquiresOriginalsAndStopsAtItsEnd() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages += listOf(f.past.event(2), f.past.event(1))
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            assertTrue(vm.state.value.hasOlder)
            assertTrue(f.past.requestedPositions.isEmpty())
            vm.onEvent(ChatThreadEvent.OlderRequested)
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertEquals(listOf<String?>(null), f.past.requestedPositions)
            assertEquals(
                listOf("Older message 2", "Older message 1"),
                vm.state.value.rows.map { it.text },
            )
            assertEquals(2, f.past.anchors.size)
            assertTrue(f.past.references.all { it.resolved })
            assertFalse(vm.state.value.hasOlder)
            assertFalse(vm.state.value.historyIncomplete)
            assertFalse(vm.state.value.loadingOlder)
            assertEquals(0, f.deliveries)
        }
    }

    @Test
    fun providerPositionContinuesAcrossRequestsWithoutRepeatingTheCommittedPage() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages += listOf(f.past.event(2))
            f.past.pages += listOf(f.past.event(1))
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertTrue(vm.state.value.hasOlder)
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertEquals(listOf(null, "provider-1"), f.past.requestedPositions)
            assertEquals(2, vm.state.value.rows.size)
            assertFalse(vm.state.value.hasOlder)
        }
    }

    @Test
    fun unknownReferencesDoNotStarveLaterPagesAndExhaustionNeverClaimsDecryption() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages += List(20) { f.past.event(40 - it, readable = false) }
            f.past.pages += listOf(f.past.event(1))
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            repeat(3) {
                vm.onEvent(ChatThreadEvent.OlderRequested)
                advanceUntilIdle()
            }
            assertEquals(2, f.past.requestedPositions.size)
            assertEquals("Older message 1", vm.state.value.rows.single().text)
            assertEquals(20, f.past.references.count { !it.resolved })
            assertTrue(vm.state.value.historyIncomplete)
            assertFalse(vm.state.value.hasOlder)
            assertTrue(f.past.anchors.size <= 6)
        }
    }

    @Test
    fun failedOriginalReadRetriesCommittedReferencesBeforeFetchingAnotherProviderPage() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages += listOf(f.past.event(2))
            f.past.pages += listOf(f.past.event(1))
            f.past.originalFailure = f.base.network
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertEquals(f.base.network, vm.state.value.historyFailure)
            assertTrue(vm.state.value.hasOlder)
            assertEquals(1, f.past.stages)
            f.past.originalFailure = null
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertNull(vm.state.value.historyFailure)
            assertEquals(1, f.past.requestedPositions.size)
            assertEquals("Older message 2", vm.state.value.rows.single().text)
        }
    }

    @Test
    fun stoppingDuringUncooperativeProviderReadDoesNotStageOrPublishIt() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages += listOf(f.past.event(1))
            val release = CompletableDeferred<Unit>()
            f.past.beforeProvider = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            runCurrent()
            assertTrue(vm.state.value.loadingOlder)
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(0, f.past.stages)
            assertTrue(vm.state.value.rows.isEmpty())
            assertFalse(vm.state.value.loadingOlder)
            assertEquals(ChatThreadConnection.OFFLINE, vm.state.value.connection)
            assertEquals(1, f.timelineCloses)
        }
    }

    @Test
    fun lateAccessDenialCannotPublishOrAcknowledgeProviderContent() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages += listOf(f.past.event(1))
            val release = CompletableDeferred<Unit>()
            f.past.beforeOriginal = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            runCurrent()
            assertEquals(1, f.past.stages)
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertTrue(f.past.references.none { it.resolved })
            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals(f.base.denied, vm.state.value.localFailure)
            assertEquals(1, f.timelineCloses)
        }
    }

    @Test
    fun emptyIntermediateProviderPageCanContinueToAnOlderMessage() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages += emptyList<CollaborationProviderHistoryEvent>()
            f.past.pages += listOf(f.past.event(1))
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertEquals("Older message 1", vm.state.value.rows.single().text)
            assertFalse(vm.state.value.hasOlder)
            assertEquals(2, f.past.requestedPositions.size)
            assertEquals(listOf("\$older-1"), f.past.anchors)
        }
    }

    @Test
    fun explicitRefreshRetriesUnavailableOriginalsWithoutRefetchingAnExhaustedProvider() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages += listOf(f.past.event(1, readable = false))
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertTrue(vm.state.value.historyIncomplete)
            f.past.event(1)
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            advanceUntilIdle()
            assertEquals("Older message 1", vm.state.value.rows.single().text)
            assertFalse(vm.state.value.historyIncomplete)
            assertFalse(vm.state.value.hasOlder)
            assertEquals(1, f.past.requestedPositions.size)
        }
    }

    @Test
    fun incomingOriginalsAcrossManyProviderPagesNeverSpendAnotherNativeFocus() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            val events = (1..175).map { f.past.event(it) }
            for (event in events) {
                f.past.facts[event.eventId] =
                    CollaborationHistoryEventFact(
                        event.eventId,
                        event.sender,
                        event.timestamp,
                        false,
                    )
            }
            f.past.pages.addAll(events.chunked(20))
            // Every original is durable, including ones outside the currently visible window.
            // Any new native focus would fail, as it does after exhausting the real SDK budget.
            f.past.originalFailure = f.base.network
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            repeat(20) {
                vm.onEvent(ChatThreadEvent.OlderRequested)
                advanceUntilIdle()
                assertNull(vm.state.value.historyFailure)
            }
            assertEquals(9, f.past.requestedPositions.size)
            assertTrue(f.past.anchors.isEmpty())
            assertEquals(175, f.past.references.count { it.resolved })
            assertFalse(vm.state.value.hasOlder)
            assertFalse(vm.state.value.historyIncomplete)
        }
    }

    @Test
    fun mixedProviderPageFocusesOnlyTheMissingOriginalBetweenRetainedNeighbors() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            val events = (1..3).map { f.past.event(it) }
            for (event in listOf(events.first(), events.last())) {
                f.past.facts[event.eventId] =
                    CollaborationHistoryEventFact(
                        event.eventId,
                        event.sender,
                        event.timestamp,
                        false,
                    )
            }
            f.past.pages += events
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertEquals(listOf(events[1].eventId), f.past.anchors)
            assertEquals(1, f.past.requestedPositions.size)
            assertTrue(f.past.references.all { it.resolved })
            assertNull(vm.state.value.historyFailure)
            assertFalse(vm.state.value.hasOlder)
        }
    }

    @Test
    fun olderCapacityFailureReopensTheFailedAnchorAndKeepsCommittedProviderProgress() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            val event = f.past.event(1)
            f.past.pages += listOf(event)
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            f.historyFull = true
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertEquals(
                "client.collaboration_history_focus_capacity",
                vm.state.value.historyFailure?.code,
            )
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            advanceUntilIdle()
            assertEquals(listOf(event.eventId), f.reopenedAnchors)
            assertEquals(1, f.past.requestedPositions.size)
            assertEquals("Older message 1", vm.state.value.rows.single().text)
            assertNull(vm.state.value.historyFailure)
            assertFalse(vm.state.value.hasOlder)
            assertEquals(1, f.timelineCloses)
            assertEquals(2, f.timelineOpens)
            assertEquals(0, f.deliveries)
        }
    }

    @Test
    fun unrelatedStateReferencesRemainStoredWithoutClaimingMessagesAreMissing() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages +=
                listOf(
                    f.past.event(2, readable = false).copy(type = "m.room.member"),
                    f.past.event(1),
                )
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertEquals("Older message 1", vm.state.value.rows.single().text)
            assertFalse(vm.state.value.historyIncomplete)
            assertFalse(vm.state.value.hasOlder)
            assertEquals(1, f.past.references.count { !it.resolved })
        }
    }

    @Test
    fun stateOnlyPageKeepsReferencesWithoutOpeningNativeMessageWindows() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages +=
                listOf(
                    f.past.event(2, readable = false).copy(type = "m.room.member"),
                    f.past.event(1, readable = false).copy(type = "m.room.create"),
                )
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertTrue(f.past.anchors.isEmpty())
            assertEquals(2, f.past.references.count { !it.resolved })
            assertTrue(vm.state.value.rows.isEmpty())
            assertFalse(vm.state.value.hasOlder)
            assertFalse(vm.state.value.historyIncomplete)
        }
    }

    @Test
    fun concurrentProviderGrowthCannotPublishThePreviousSnapshotsEnd() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.past.pages += listOf(f.past.event(1))
            var extended = false
            f.past.beforeOriginal = {
                if (!extended) {
                    extended = true
                    val event = f.past.event(2)
                    f.past.references += CollaborationHistoryReference(1, event)
                    f.past.progress = requireNotNull(f.past.progress).copy(references = 2)
                }
            }
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertTrue(vm.state.value.hasOlder)
            vm.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertFalse(vm.state.value.hasOlder)
            assertEquals(2, vm.state.value.rows.size)
            assertEquals(1, f.past.requestedPositions.size)
            assertTrue(f.past.references.all { it.resolved })
        }
    }

    @Test
    fun pendingSdkEchoChangesDoNotBypassTheApplicationDeliveryBackoff() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            val entryReads = f.historyReads
            vm.onEvent(ChatThreadEvent.DraftChanged("retained original"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            advanceUntilIdle()
            assertEquals(entryReads, f.historyReads, "Local enqueue already wakes delivery")
            val original = f.publishQueuedComposer()
            runCurrent()
            val reads = f.historyReads
            f.scheduledKeys.clear()
            for (status in
                listOf(
                    CollaborationMessageDelivery.FAILED,
                    CollaborationMessageDelivery.PENDING,
                    CollaborationMessageDelivery.FAILED,
                    CollaborationMessageDelivery.PENDING,
                )) {
                f.updates.value =
                    AppResult.Success(
                        listOf(
                            CollaborationTimelineItem(
                                "local-echo",
                                null,
                                "original-tx",
                                original.intent.id,
                                f.self.matrixUserId,
                                1_000,
                                true,
                                CollaborationMessageKind.TEXT,
                                original.intent.text,
                                false,
                                status,
                                localEcho = true,
                            )
                        )
                    )
                advanceTimeBy(500)
                runCurrent()
            }
            assertEquals(reads, f.historyReads, "Local delivery status must not reacquire history")
            assertTrue(f.scheduledKeys.isEmpty(), "Passive echoes must not restart delivery")
            assertEquals(listOf(original), f.records.value)
            assertEquals(0, f.deliveries)
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            advanceUntilIdle()
            assertEquals(1, f.scheduledKeys.size, "Explicit retry must still wake delivery")
            f.scheduledKeys.clear()
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            vm.onEvent(ChatThreadEvent.Entered())
            advanceUntilIdle()
            assertTrue(f.scheduledKeys.isEmpty(), "Route reentry cannot restart blocked delivery")
            repeat(3) {
                val opened = f.timelineOpens
                f.updates.value =
                    AppResult.Failed(
                        Failure(
                            FailureKind.Unavailable,
                            "Timeline closed",
                            code = "client.collaboration_timeline_closed",
                        )
                    )
                runCurrent()
                f.updates.value = AppResult.Success(emptyList())
                advanceTimeBy(2_500)
                runCurrent()
                assertEquals(opened + 1, f.timelineOpens)
                assertTrue(f.scheduledKeys.isEmpty(), "Automatic reconnect cannot retry delivery")
                assertEquals(listOf(original), f.records.value)
            }
        }
    }

    @Test
    fun remoteContentChangesAcquireHistoryButLocalAcknowledgementAndSdkRowIdentityDoNot() =
        runTest {
            withModel { f, vm, _, _ ->
                f.online()
                vm.onEvent(ChatThreadEvent.Entered())
                advanceUntilIdle()
                val entered = f.historyReads
                val local =
                    CollaborationTimelineItem(
                        "local-row",
                        "$" + "acknowledged",
                        "local-transaction",
                        null,
                        f.self.matrixUserId,
                        1_000,
                        true,
                        CollaborationMessageKind.TEXT,
                        "Fixture message",
                        false,
                        CollaborationMessageDelivery.SENT,
                        localEcho = true,
                    )
                f.updates.value = AppResult.Success(listOf(local))
                advanceUntilIdle()
                assertEquals(entered, f.historyReads, "A local ACK is not an original remote event")
                val remote = local.copy(key = "remote-row", localEcho = false)
                f.updates.value = AppResult.Success(listOf(remote))
                advanceUntilIdle()
                assertEquals(entered + 1, f.historyReads)
                val recreated = remote.copy(key = "recreated-row", transactionId = null)
                f.updates.value = AppResult.Success(listOf(recreated))
                advanceUntilIdle()
                assertEquals(entered + 1, f.historyReads, "SDK row metadata is not new history")
                val edited = recreated.copy(body = "Edited fixture", edited = true)
                f.updates.value = AppResult.Success(listOf(edited))
                advanceUntilIdle()
                assertEquals(entered + 2, f.historyReads)
                val redacted = edited.copy(kind = CollaborationMessageKind.REDACTED, body = null)
                f.updates.value = AppResult.Success(listOf(redacted))
                advanceUntilIdle()
                assertEquals(entered + 3, f.historyReads)
                vm.onEvent(ChatThreadEvent.Stopped)
                advanceUntilIdle()
                f.updates.value = AppResult.Success(listOf(remote))
                advanceUntilIdle()
                assertEquals(entered + 3, f.historyReads, "A stopped screen owns no acquisition")
                vm.onEvent(ChatThreadEvent.Entered())
                advanceUntilIdle()
                assertTrue(f.historyReads > entered + 3, "Reentry still requests admitted history")
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
