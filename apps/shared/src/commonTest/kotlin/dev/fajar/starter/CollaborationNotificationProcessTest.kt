@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.datasources.ActionNotificationDisplaySource
import dev.fajar.starter.notifications.data.dto.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

/** Real mechanical owner, virtual time, fake journal/policy/OS ports; not provider acceptance. */
class CollaborationNotificationProcessTest {
    @Test
    fun deliveryCannotSucceedWithoutAHostAndAcceptsOnlyOneOwnedProcess() = runTest {
        fixture { f ->
            f.references = emptyList()
            val delivery = CollaborationNotificationDelivery()
            assertIs<AppResult.Failed>(delivery.publish(f.parent, f.candidate()))
            assertIs<AppResult.Failed>(delivery.retry())
            assertEquals(0, f.candidateCalls)
            assertTrue(delivery.start(f.process))
            assertFalse(delivery.start(f.process))
            try {
                assertIs<AppResult.Success<*>>(delivery.publish(f.parent, f.candidate(), true))
                assertEquals(listOf(true), f.alertRequests)
            } finally {
                delivery.closeAndJoin()
            }
            assertFalse(delivery.start(f.process))
            assertIs<AppResult.Failed>(delivery.publish(f.parent, f.candidate()))
            assertTrue(f.native.cards.isEmpty())
        }
    }

    @Test
    fun closingDeliveryResolvesSourceWaitingRequestsAndCannotReopen() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.signals.resetReplayCache()
            val delivery = CollaborationNotificationDelivery()
            assertTrue(delivery.start(f.process))
            val request = async { delivery.publish(f.parent, f.candidate()) }
            runCurrent()
            assertFalse(request.isCompleted)
            delivery.closeAndJoin()
            assertIs<AppResult.Failed>(request.await())
            assertEquals(f.opened, f.closed)
            assertEquals(0, f.candidateCalls)
            assertFalse(delivery.start(f.process))
        }
    }

    @Test
    fun closingDeliveryJoinsLateNativeCompletionAndCompensationBeforeReturning() = runTest {
        fixture { f ->
            f.references = emptyList()
            val release = CompletableDeferred<Unit>()
            f.native.onShow = { withContext(NonCancellable) { release.await() } }
            val delivery = CollaborationNotificationDelivery()
            assertTrue(delivery.start(f.process))
            val request = async { delivery.publish(f.parent, f.candidate()) }
            runCurrent()
            assertEquals(1, f.native.cards.size)
            val closed = async { delivery.closeAndJoin() }
            try {
                runCurrent()
                assertFalse(closed.isCompleted)
                assertFalse(request.isCompleted)
            } finally {
                release.complete(Unit)
            }
            closed.await()
            assertIs<AppResult.Failed>(request.await())
            assertTrue(f.native.cards.isEmpty())
            assertEquals(0, f.publications)
            assertEquals(f.opened, f.closed)
        }
    }

    @Test
    fun candidateWaitsForTheBusySurfaceCompletionWithoutDroppingItsRequest() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.start()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var blocker: Job? = null
            f.beforePublish = {
                if (blocker == null) {
                    blocker =
                        backgroundScope.launch {
                            f.owner.withOwner(f.parent) {
                                entered.complete(Unit)
                                release.await()
                                AppResult.Success(Unit)
                            }
                        }
                    entered.await()
                }
            }
            try {
                val request = async { f.process.publish(f.parent, f.candidate()) }
                runCurrent()
                assertFalse(request.isCompleted)
                assertEquals(1, f.candidateCalls)
                assertTrue(f.native.cards.isEmpty())
                release.complete(Unit)
                runCurrent()
                assertIs<AppResult.Success<*>>(request.await())
                assertEquals(2, f.candidateCalls)
                assertEquals(1, f.native.cards.size)
            } finally {
                withContext(NonCancellable) { blocker?.cancelAndJoin() }
            }
        }
    }

    @Test
    fun cancellingOneOfTwoCandidatesKeepsTheirSharedSourceForTheOther() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.signals.resetReplayCache()
            f.start()
            val first = async { f.process.publish(f.parent, f.candidate()) }
            val second = async { f.process.publish(f.parent, f.candidate()) }
            runCurrent()
            assertEquals(1, f.opened)
            first.cancelAndJoin()
            runCurrent()
            assertEquals(0, f.closed)
            f.signals.emit(AppResult.Success(Unit))
            runCurrent()
            assertIs<AppResult.Success<*>>(second.await())
            assertEquals(1, f.candidateCalls)
            assertEquals(1, f.opened)
        }
    }

    @Test
    fun cancelledCandidateWaitsForLateNativeCompletionAndExactCompensation() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.start()
            val release = CompletableDeferred<Unit>()
            f.native.onShow = { withContext(NonCancellable) { release.await() } }
            val request = async { f.process.publish(f.parent, f.candidate()) }
            runCurrent()
            val cancelled = launch { request.cancelAndJoin() }
            try {
                runCurrent()
                assertFalse(cancelled.isCompleted)
                assertIs<AppResult.Failed>(f.owner.withOwner(f.parent) { AppResult.Success(Unit) })
                release.complete(Unit)
                cancelled.join()
                runCurrent()
                assertTrue(request.isCancelled)
                assertTrue(f.native.cards.isEmpty())
                assertEquals(0, f.publications)
                assertIs<AppResult.Success<*>>(
                    f.owner.withOwner(f.parent) { AppResult.Success(Unit) }
                )
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) {
                    cancelled.cancelAndJoin()
                    request.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun candidatesProgressBetweenRepeatedDirtyReferenceTurns() = runTest {
        fixture { f ->
            f.start()
            val events = mutableListOf<String>()
            f.afterCommit = {
                events += "reconcile"
                if (events.count { it == "reconcile" } < 3) f.signals.emit(AppResult.Success(Unit))
            }
            f.beforePublish = { events += "candidate" }
            val request =
                async(start = CoroutineStart.UNDISPATCHED) {
                    f.process.publish(f.parent, f.candidate(2))
                }
            assertTrue(f.signals.tryEmit(AppResult.Success(Unit)))
            runCurrent()
            assertIs<AppResult.Success<*>>(request.await())
            assertTrue(events.count { it == "reconcile" } >= 3)
            val third = events.withIndex().filter { it.value == "reconcile" }[2].index
            assertTrue(events.indexOf("candidate") < third)
        }
    }

    @Test
    fun newlyDiscoveredFullJournalReleasesExcessProvisionalSlotsBeforeAddingSources() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.signals.resetReplayCache()
            f.start()
            val request = async { f.process.publish(f.parent, f.candidate()) }
            runCurrent()
            assertEquals(1, f.opened)
            f.references = (2..65).map { f.reference(it) }
            f.process.requestScan()
            runCurrent()
            assertIs<AppResult.Failed>(request.await())
            assertEquals(64, f.opened - f.closed)
            assertEquals(1, f.closed)
            assertEquals(0, f.candidateCalls)
        }
    }

    @Test
    fun candidateWaitsForItsFirstSourceAndPromotesWithoutDuplicateObservationOrReplay() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.signals.resetReplayCache()
            f.start()
            val request = async { f.process.publish(f.parent, f.candidate(), allowAlert = true) }
            runCurrent()
            assertFalse(request.isCompleted)
            assertEquals(1, f.opened)
            assertEquals(0, f.candidateCalls)
            f.signals.emit(AppResult.Success(Unit))
            runCurrent()
            assertIs<AppResult.Success<*>>(request.await())
            assertEquals(listOf(true), f.alertRequests)
            assertEquals(1, f.opened)
            assertEquals(0, f.closed)
            assertEquals(0, f.publications)
            assertEquals(1, f.native.cards.size)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, f.candidateCalls)
            assertEquals(0, f.publications)
        }
    }

    @Test
    fun cancelledWaitingCandidateReleasesItsProvisionalSource() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.signals.resetReplayCache()
            f.start()
            val request = async { f.process.publish(f.parent, f.candidate()) }
            runCurrent()
            assertEquals(1, f.opened)
            request.cancelAndJoin()
            runCurrent()
            assertEquals(1, f.closed)
            f.signals.emit(AppResult.Success(Unit))
            runCurrent()
            assertEquals(0, f.candidateCalls)
            assertTrue(f.references.isEmpty())
        }
    }

    @Test
    fun twentyPendingRequestsBoundTheQueueAndTimeoutReleasesAllProvisionalSources() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.signals.resetReplayCache()
            f.start()
            val requests =
                (1..20).map { index -> async { f.process.publish(f.parent, f.candidate(index)) } }
            runCurrent()
            assertEquals(20, f.opened)
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.process.publish(f.parent, f.candidate(21)))
                    .failure
                    .kind,
            )
            assertEquals(0, f.candidateCalls)
            advanceTimeBy(8_001)
            runCurrent()
            requests.forEach {
                assertEquals(
                    FailureKind.Timeout,
                    assertIs<AppResult.Failed>(it.await()).failure.kind,
                )
            }
            assertEquals(20, f.closed)
            f.signals.emit(AppResult.Success(Unit))
            assertIs<AppResult.Success<*>>(f.process.publish(f.parent, f.candidate(21)))
            runCurrent()
            assertEquals(1, f.candidateCalls)
        }
    }

    @Test
    fun malformedAndForeignCandidatesNeverOpenTheirSource() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.start()
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(
                        f.process.publish(f.parent, f.candidate().copy(eventId = "invalid"))
                    )
                    .failure
                    .kind,
            )
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(
                        f.process.publish(f.parent.copy(accountId = uuid(999)), f.candidate())
                    )
                    .failure
                    .kind,
            )
            assertEquals(0, f.opened)
            assertEquals(0, f.candidateCalls)
        }
    }

    @Test
    fun parentReplacementRejectsOldQueuedRequestsBeforeReadingTheNewJournal() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.signals.resetReplayCache()
            f.start()
            val request = async { f.process.publish(f.parent, f.candidate()) }
            runCurrent()
            f.onRead = { awaitCancellation() }
            f.parents.value = f.parent.copy(accountId = uuid(999))
            runCurrent()
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(request.await()).failure.kind,
            )
            assertEquals(1, f.closed)
            assertEquals(0, f.candidateCalls)
        }
    }

    @Test
    fun cancellationAfterPreparationJoinsWorkAndLeavesQuietRecoverableJournal() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.start()
            var joined = false
            f.afterPublishPrepared = {
                try {
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            val request = async { f.process.publish(f.parent, f.candidate()) }
            runCurrent()
            assertEquals(
                CollaborationNotificationPublicationState.Prepared,
                f.references.single().state,
            )
            request.cancelAndJoin()
            runCurrent()
            assertTrue(joined)
            assertTrue(f.native.cards.isEmpty())
            assertEquals(0, f.publications)
            assertEquals(1, f.opened)
            f.afterPublishPrepared = {}
            assertIs<AppResult.Success<*>>(f.process.retry())
            runCurrent()
            assertEquals(1, f.native.cards.size)
        }
    }

    @Test
    fun sourceFailureDuringCandidatePublicationJoinsCompensationBeforeFailure() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.start()
            var joined = false
            f.native.onShow = {
                try {
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            val request = async { f.process.publish(f.parent, f.candidate()) }
            runCurrent()
            assertEquals(1, f.native.cards.size)
            f.signals.emit(unavailable())
            runCurrent()
            assertIs<AppResult.Failed>(request.await())
            assertTrue(joined)
            assertTrue(f.native.cards.isEmpty())
            assertTrue(f.native.removals >= 1)
            assertEquals(
                CollaborationNotificationPublicationState.Prepared,
                f.references.single().state,
            )
        }
    }

    @Test
    fun candidateSharesAnExistingConversationWatchAndTheTotalSlotBound() = runTest {
        fixture { f ->
            f.references = (1..64).map { f.reference(it) }
            f.start()
            assertIs<AppResult.Failed>(f.process.publish(f.parent, f.candidate(65)))
            assertEquals(64, f.opened)
            assertEquals(0, f.candidateCalls)
            assertIs<AppResult.Success<*>>(f.process.publish(f.parent, f.candidate(1)))
            runCurrent()
            assertEquals(64, f.opened)
            assertEquals(1, f.candidateCalls)
        }
    }

    @Test
    fun closeSettlesWaitingRequestsAndPreventsReopeningTheQueue() = runTest {
        fixture { f ->
            f.references = emptyList()
            f.signals.resetReplayCache()
            f.start()
            val request = async { f.process.publish(f.parent, f.candidate()) }
            runCurrent()
            f.process.closeAndJoin()
            assertIs<AppResult.Failed>(request.await())
            assertEquals(1, f.closed)
            assertIs<AppResult.Failed>(f.process.publish(f.parent, f.candidate()))
            assertEquals(0, f.candidateCalls)
        }
    }

    @Test
    fun duplicateConversationReferencesCannotCollapseIntoOneSourceSlot() = runTest {
        fixture { f ->
            val first = f.reference(1)
            f.references = listOf(first, f.reference(2).copy(target = first.target))
            f.start()
            assertEquals(CollaborationNotificationSurfaceState.Blocked, f.owner.states.first())
            assertEquals(0, f.opened)
            assertTrue(f.native.cards.isEmpty())
        }
    }

    @Test
    fun busyWorkWaitsForCompletionWithoutLosingItsDirtySignal() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var blocker: Job? = null
            f.beforeReconcile = {
                if (blocker == null) {
                    blocker =
                        backgroundScope.launch {
                            f.owner.withOwner(f.parent) {
                                entered.complete(Unit)
                                release.await()
                                AppResult.Success(Unit)
                            }
                        }
                    entered.await()
                }
            }
            try {
                f.start()
                assertEquals(0, f.publications)
                assertEquals(1, f.attempts)
                runCurrent()
                assertEquals(1, f.attempts)
                release.complete(Unit)
                runCurrent()
                assertEquals(1, f.publications)
                assertEquals(2, f.attempts)
            } finally {
                withContext(NonCancellable) { blocker?.cancelAndJoin() }
            }
        }
    }

    @Test
    fun completionAlreadyObservedWhenBusyReturnsStillRetriesTheCapturedAttempt() = runTest {
        fixture { f ->
            f.syntheticBusyOnce = true
            f.beforeReconcile = {
                if (f.syntheticBusyOnce) {
                    assertIs<AppResult.Success<*>>(
                        f.owner.withOwner(f.parent) { AppResult.Success(Unit) }
                    )
                    yield()
                }
            }
            f.start()
            assertEquals(1, f.publications)
            assertEquals(2, f.attempts)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, f.attempts)
        }
    }

    @Test
    fun uncertainObsoleteCleanupPausesInsteadOfForgettingTheNativeCard() = runTest {
        fixture { f ->
            f.references = listOf(f.reference(1), f.reference(2))
            f.start()
            f.native.onRemove = { error("Fictional unavailable cleanup") }
            f.references = listOf(f.references.last())
            f.process.requestScan()
            runCurrent()
            assertEquals(CollaborationNotificationSurfaceState.Blocked, f.owner.states.first())
            assertTrue(f.native.cards.isEmpty())
            assertEquals(2, f.closed)
            f.native.onRemove = {}
            assertIs<AppResult.Success<*>>(f.process.retry())
            runCurrent()
            assertEquals(1, f.native.cards.size)
        }
    }

    @Test
    fun obsoleteReferenceCleanupPreservesANewerNativeRender() = runTest {
        fixture { f ->
            f.start()
            val old = f.native.cards.values.single()
            val next = old.copy(reference = old.reference.copy(renderId = uuid(950)))
            f.native.cards[old.reference.id] = next
            f.references = emptyList()
            f.process.requestScan()
            runCurrent()
            assertEquals(listOf(next), f.native.cards.values.toList())
            assertEquals(1, f.closed)
        }
    }

    @Test
    fun ownAcknowledgmentRescansWithoutRestartingSourcesOrRepeatedPublication() = runTest {
        fixture { f ->
            f.start()
            assertEquals(1, f.native.cards.size)
            assertEquals(1, f.publications)
            assertEquals(1, f.opened)
            assertEquals(1, f.references.single().version.revision)
            val reads = f.reads
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(reads, f.reads)
            assertEquals(1, f.publications)
            assertEquals(1, f.opened)
        }
    }

    @Test
    fun explicitScanAddsAndReleasesSourcesAndPreservesAnUnchangedConversationWatcher() = runTest {
        fixture { f ->
            f.start()
            val original = f.references.single()
            f.references =
                listOf(
                    original.copy(version = original.version.copy(revision = 20)),
                    f.reference(2),
                )
            f.process.requestScan()
            runCurrent()
            assertEquals(2, f.opened)
            assertEquals(2, f.publications)
            f.references = listOf(f.references.last())
            f.process.requestScan()
            runCurrent()
            assertEquals(1, f.closed)
            assertEquals(2, f.opened)
            assertEquals(1, f.native.cards.size)
        }
    }

    @Test
    fun failedSourceHidesWithoutRetryingItsOwnCompletionAndRecoversOnANewSignal() = runTest {
        fixture { f ->
            f.start()
            f.signals.emit(unavailable())
            runCurrent()
            assertTrue(f.native.cards.isEmpty())
            val attempts = f.attempts
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(attempts, f.attempts)
            assertEquals(1, f.opened)
            f.signals.emit(AppResult.Success(Unit))
            runCurrent()
            assertEquals(1, f.native.cards.size)
            assertEquals(2, f.publications)
        }
    }

    @Test
    fun normalSourceCompletionRemainsClosedUntilExplicitRetry() = runTest {
        fixture { f ->
            f.flow = { flowOf(AppResult.Success(Unit)) }
            f.start()
            assertTrue(f.native.cards.isEmpty())
            val attempts = f.attempts
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(attempts, f.attempts)
            assertEquals(1, f.opened)
            assertEquals(1, f.closed)
            f.flow = { f.signals }
            assertIs<AppResult.Success<*>>(f.process.retry())
            runCurrent()
            assertEquals(2, f.opened)
            assertEquals(1, f.native.cards.size)
        }
    }

    @Test
    fun missingInitialSourceNeverGrantsPublication() = runTest {
        fixture { f ->
            f.signals.resetReplayCache()
            f.start()
            assertEquals(0, f.publications)
            assertTrue(f.native.cards.isEmpty())
            val attempts = f.attempts
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(attempts, f.attempts)
            f.signals.emit(AppResult.Success(Unit))
            runCurrent()
            assertEquals(1, f.publications)
        }
    }

    @Test
    fun sourceInvalidationJoinsPendingNativeWorkBeforeHandlingItsLatestFailure() = runTest {
        fixture { f ->
            var joined = false
            f.native.onShow = {
                try {
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            f.process.start()
            runCurrent()
            assertEquals(1, f.native.cards.size)
            f.signals.emit(unavailable())
            runCurrent()
            assertTrue(joined)
            assertTrue(f.native.cards.isEmpty())
            assertTrue(f.native.removals >= 1)
            f.native.onShow = {}
            f.signals.emit(AppResult.Success(Unit))
            runCurrent()
            assertEquals(1, f.native.cards.size)
        }
    }

    @Test
    fun parentReplacementDisposesOldSourcesAndNeverReusesTheirPendingWork() = runTest {
        fixture { f ->
            var joined = false
            f.native.onShow = {
                try {
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            f.process.start()
            runCurrent()
            f.references = emptyList()
            val next = f.parent.copy(nativeSessionId = uuid(999))
            f.parents.value = next
            runCurrent()
            assertTrue(joined)
            assertEquals(1, f.closed)
            assertTrue(f.native.cards.isEmpty())
            assertEquals(next, f.readParents.last())
            f.native.onShow = {}
            f.signals.emit(AppResult.Success(Unit))
            runCurrent()
            assertTrue(f.native.cards.isEmpty())
        }
    }

    @Test
    fun committedButCancelledPolicyWorkRescansTheCurrentReference() = runTest {
        fixture { f ->
            var interrupted = false
            f.afterCommit = {
                if (!interrupted) {
                    interrupted = true
                    f.process.requestScan()
                    awaitCancellation()
                }
            }
            f.start()
            assertTrue(interrupted)
            assertEquals(2, f.publications)
            assertEquals(2, f.references.single().version.revision)
            assertEquals(1, f.native.cards.size)
            assertEquals(1, f.opened)
        }
    }

    @Test
    fun unchangedContentionWaitsAndChangedReferenceGetsOneCurrentRetry() = runTest {
        fixture { f ->
            f.contended = true
            f.start()
            val attempts = f.attempts
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(attempts, f.attempts)
            f.process.requestScan()
            runCurrent()
            assertEquals(attempts, f.attempts)
            f.references = f.references.map { it.copy(version = it.version.copy(revision = 5)) }
            f.contended = false
            f.process.requestScan()
            runCurrent()
            assertEquals(1, f.publications)
            assertEquals(6, f.references.single().version.revision)
        }
    }

    @Test
    fun genericStorageFailureDoesNotRetryOnItsOwnSurfaceCompletion() = runTest {
        fixture { f ->
            f.policyFailure = true
            f.start()
            val attempts = f.attempts
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(attempts, f.attempts)
            assertTrue(f.native.cards.isEmpty())
            f.policyFailure = false
            f.signals.emit(AppResult.Success(Unit))
            runCurrent()
            assertEquals(1, f.publications)
        }
    }

    @Test
    fun failedDiscoveryPausesWithoutSourcesAndExplicitRetryReopens() = runTest {
        fixture { f ->
            f.readFailure = true
            f.start()
            assertEquals(CollaborationNotificationSurfaceState.Blocked, f.owner.states.first())
            assertEquals(0, f.opened)
            val reads = f.reads
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(reads, f.reads)
            f.readFailure = false
            assertIs<AppResult.Success<*>>(f.process.retry())
            runCurrent()
            assertEquals(1, f.publications)
        }
    }

    @Test
    fun wholeScanDeadlineCancelsAndBlocksWithoutApplyingPartialPages() = runTest {
        fixture { f ->
            f.references = (1..5).map { f.reference(it) }
            var joined = false
            f.onRead = { after ->
                if (after != null)
                    try {
                        awaitCancellation()
                    } finally {
                        joined = true
                    }
            }
            f.process.start()
            runCurrent()
            assertEquals(0, f.opened)
            advanceTimeBy(8_001)
            runCurrent()
            assertTrue(joined)
            assertEquals(0, f.opened)
            assertEquals(CollaborationNotificationSurfaceState.Blocked, f.owner.states.first())
        }
    }

    @Test
    fun overCapacityAndForeignInactiveMalformedReferencesNeverStartSources() = runTest {
        for (invalid in 0..3) fixture { f ->
            f.references =
                when (invalid) {
                    0 -> (1..65).map { f.reference(it) }
                    1 -> listOf(f.reference(1).copy(parent = f.parent.copy(accountId = uuid(998))))
                    2 ->
                        listOf(
                            f.reference(1)
                                .copy(state = CollaborationNotificationPublicationState.Cancelled)
                        )
                    else -> listOf(f.reference(1).copy(id = "malformed"))
                }
            f.start()
            assertEquals(0, f.opened)
            assertEquals(0, f.publications)
            assertEquals(CollaborationNotificationSurfaceState.Blocked, f.owner.states.first())
        }
    }

    @Test
    fun sixtyFourReferencesUseBoundedSourcesAndCloseJoinsThemAll() = runTest {
        fixture { f ->
            f.references = (1..64).map { f.reference(it) }
            f.start()
            assertEquals(64, f.opened)
            assertEquals(64, f.publications)
            f.process.start()
            runCurrent()
            assertEquals(64, f.opened)
            f.process.closeAndJoin()
            assertEquals(64, f.closed)
            assertTrue(f.native.cards.isEmpty())
            assertFalse(f.process.requestScan())
            assertIs<AppResult.Failed>(f.process.retry())
        }
    }

    @Test
    fun closeJoinsPendingNativeWorkAndLateSignalsCannotRestartIt() = runTest {
        fixture { f ->
            var joined = false
            f.native.onShow = {
                try {
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            f.process.start()
            runCurrent()
            f.process.closeAndJoin()
            assertTrue(joined)
            assertEquals(1, f.closed)
            assertTrue(f.native.cards.isEmpty())
            val attempts = f.attempts
            f.signals.emit(AppResult.Success(Unit))
            f.process.start()
            runCurrent()
            assertEquals(attempts, f.attempts)
        }
    }

    @Test
    fun closeBeforeStartDoesNotOpenSourcesOrNativeTurns() = runTest {
        fixture { f ->
            f.process.closeAndJoin()
            f.process.start()
            runCurrent()
            assertEquals(0, f.reads)
            assertEquals(0, f.opened)
            assertEquals(CollaborationNotificationSurfaceState.Closed, f.owner.states.first())
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        try {
            block(f)
        } finally {
            f.process.closeAndJoin()
        }
    }

    private class Fixture(private val test: TestScope) {
        val parent =
            CollaborationNativeParent("local", "https://example.test/api/", uuid(900), uuid(901))
        val parents = MutableStateFlow<CollaborationNativeParent?>(parent)
        val native = Native()
        val owner =
            CollaborationNotificationSurfaceOwner(
                parents,
                native,
                StandardTestDispatcher(test.testScheduler),
            )
        val signals =
            MutableSharedFlow<AppResult<Unit>>(replay = 1).also {
                it.tryEmit(AppResult.Success(Unit))
            }
        var references = listOf(reference(1))
        var flow: () -> Flow<AppResult<Unit>> = { signals }
        var onRead: suspend (String?) -> Unit = {}
        var afterCommit: suspend () -> Unit = {}
        var afterPublishPrepared: suspend () -> Unit = {}
        var beforePublish: suspend () -> Unit = {}
        var candidateCalls = 0
        val alertRequests = mutableListOf<Boolean>()
        var beforeReconcile: suspend () -> Unit = {}
        var syntheticBusyOnce = false
        var readFailure = false
        var policyFailure = false
        var contended = false
        var reads = 0
        var opened = 0
        var closed = 0
        var attempts = 0
        var publications = 0
        val readParents = mutableListOf<CollaborationNativeParent>()
        val process =
            CollaborationNotificationProcess(
                owner,
                read = { captured, after ->
                    reads++
                    readParents += captured
                    onRead(after)
                    if (readFailure) unavailable()
                    else {
                        val found =
                            references.filter { after == null || it.id > after }.sortedBy { it.id }
                        val page = found.take(4)
                        AppResult.Success(
                            CollaborationNotificationPublicationReferences(
                                captured,
                                page,
                                if (found.size > 4) page.last().id else null,
                            )
                        )
                    }
                },
                observe = { _, _, _ ->
                    flow {
                        opened++
                        try {
                            emitAll(flow())
                        } finally {
                            closed++
                        }
                    }
                },
                reconcile = { captured, reference, failure ->
                    attempts++
                    beforeReconcile()
                    if (syntheticBusyOnce) {
                        syntheticBusyOnce = false
                        AppResult.Failed(
                            Failure(
                                FailureKind.Unavailable,
                                "Fictional late busy outcome",
                                code = NOTIFICATION_SURFACE_BUSY,
                            )
                        )
                    } else
                        owner.withOwner(captured) {
                            when {
                                policyFailure -> unavailable()
                                contended ->
                                    AppResult.Success(
                                        CollaborationNotificationReconciliationResult.Contended
                                    )
                                failure != null -> {
                                    remove(
                                        ActionNotificationReference(
                                            reference.target.notificationId,
                                            reference.target.renderId,
                                        )
                                    )
                                    AppResult.Success(
                                        CollaborationNotificationReconciliationResult.Hidden
                                    )
                                }
                                else -> {
                                    assertIs<AppResult.Success<*>>(show(payload(reference)))
                                    publications++
                                    references =
                                        references.map {
                                            if (it.id == reference.id)
                                                it.copy(
                                                    version =
                                                        it.version.copy(
                                                            revision = it.version.revision + 1
                                                        )
                                                )
                                            else it
                                        }
                                    afterCommit()
                                    AppResult.Success(
                                        CollaborationNotificationReconciliationResult.Submitted
                                    )
                                }
                            }
                        }
                },
                publishCandidate = { captured, candidate, alert ->
                    publish(captured, candidate, alert)
                },
                dispatcher = StandardTestDispatcher(test.testScheduler),
            )

        private suspend fun publish(
            captured: CollaborationNativeParent,
            candidate: CollaborationNotificationCandidate,
            alert: Boolean,
        ): AppResult<CollaborationNotificationPublicationResult> {
            candidateCalls++
            beforePublish()
            return owner.withOwner(captured) {
                alertRequests += alert
                val index = candidate.conversationId.takeLast(12).toInt(16) - 100
                val base = reference(index)
                val prepared =
                    base.copy(
                        parent = captured,
                        target =
                            base.target.copy(
                                roomId = candidate.roomId,
                                eventId = candidate.eventId,
                                renderId = uuid(5000 + candidateCalls),
                            ),
                        state = CollaborationNotificationPublicationState.Prepared,
                    )
                references =
                    (references.filter { it.id != prepared.id } + prepared).sortedBy { it.id }
                afterPublishPrepared()
                val shown = show(payload(prepared).copy(silent = !alert))
                if (shown is AppResult.Failed) return@withOwner shown
                references =
                    references.map {
                        if (it.id == prepared.id)
                            it.copy(
                                state = CollaborationNotificationPublicationState.Published,
                                version = it.version.copy(revision = 1),
                            )
                        else it
                    }
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted)
            }
        }

        fun candidate(index: Int = 1): CollaborationNotificationCandidate =
            reference(index).target.let {
                CollaborationNotificationCandidate(
                    it.companyId,
                    it.conversationId,
                    it.roomId,
                    it.eventId,
                )
            }

        fun start() {
            process.start()
            test.runCurrent()
        }

        fun reference(index: Int) =
            CollaborationNotificationPublicationReference(
                "p-" + index.toString(16).padStart(64, '0'),
                CollaborationNotificationPublicationRevision(uuid(902), 0),
                parent,
                CollaborationNotificationActionTarget(
                    uuid(903),
                    uuid(100 + index),
                    "!room-$index:example.test",
                    "\$event-$index",
                    uuid(200 + index),
                    uuid(300 + index),
                ),
                CollaborationNotificationPublicationState.Published,
            )
    }

    private class Native : ActionNotificationDisplaySource {
        val cards = linkedMapOf<String, ActionNotificationPayload>()
        var removals = 0
        var onShow: suspend () -> Unit = {}
        var onRemove: suspend () -> Unit = {}

        override suspend fun show(
            payload: ActionNotificationPayload
        ): ActionNotificationSubmission {
            cards[payload.reference.id] = payload
            onShow()
            return ActionNotificationSubmission.Submitted
        }

        override suspend fun active() = cards.values.map { it.reference }

        override suspend fun remove(
            expected: ActionNotificationReference
        ): ActionNotificationRemoval {
            removals++
            onRemove()
            val card = cards[expected.id] ?: return ActionNotificationRemoval.Absent
            if (card.reference != expected) return ActionNotificationRemoval.Replaced
            cards.remove(expected.id)
            return ActionNotificationRemoval.Removed
        }

        override suspend fun clear() {
            cards.clear()
        }
    }

    companion object {
        private fun uuid(index: Int) =
            "00000000-0000-0000-0000-" + index.toString(16).padStart(12, '0')

        private fun unavailable() =
            AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Fictional unavailable source",
                    code = "fixture.source_unavailable",
                )
            )

        private fun payload(reference: CollaborationNotificationPublicationReference) =
            ActionNotificationPayload(
                ActionNotificationReference(
                    reference.target.notificationId,
                    reference.target.renderId,
                ),
                "Chat",
                "New message",
                ActionNotificationKind.entries.associateWith { "opaque-envelope" },
                true,
            )
    }
}
