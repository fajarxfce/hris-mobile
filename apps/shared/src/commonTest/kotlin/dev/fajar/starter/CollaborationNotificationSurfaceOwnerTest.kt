@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.datasources.ActionNotificationDisplaySource
import dev.fajar.starter.notifications.data.dto.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

/** Mechanical lifetime tests, with virtual time and a fake OS surface; not HRIS/OS integration. */
class CollaborationNotificationSurfaceOwnerTest {
    @Test
    fun coldStateClosesForLateCollectorsAndAfterCloseBeforeStart() = runTest {
        withFixture { f ->
            assertEquals(CollaborationNotificationSurfaceState.Pending, f.owner.states.first())
            f.owner.closeAndJoin()
            f.owner.start()
            assertEquals(CollaborationNotificationSurfaceState.Closed, f.owner.states.first())
        }
        withFixture { f ->
            f.start()
            assertIs<CollaborationNotificationSurfaceState.Ready>(f.owner.states.first())
            f.owner.closeAndJoin()
            assertEquals(CollaborationNotificationSurfaceState.Closed, f.owner.states.first())
        }
    }

    @Test
    fun stateWaitsForCleanupAndWakesAfterBusyWorkReleasesSerialization() = runTest {
        withFixture { f ->
            val cleanup = CompletableDeferred<Unit>()
            f.source.onClear = { cleanup.await() }
            f.owner.start()
            runCurrent()
            assertEquals(CollaborationNotificationSurfaceState.Pending, f.owner.states.first())
            cleanup.complete(Unit)
            runCurrent()
            val idle = assertIs<CollaborationNotificationSurfaceState.Ready>(f.owner.states.first())
            assertTrue(idle.isIdle)
            val release = CompletableDeferred<Unit>()
            f.source.onShow = { release.await() }
            val work = async { f.show() }
            runCurrent()
            val busy = assertIs<CollaborationNotificationSurfaceState.Ready>(f.owner.states.first())
            assertFalse(busy.isIdle)
            assertSame(idle.epoch, busy.epoch)
            release.complete(Unit)
            assertIs<AppResult.Success<*>>(work.await())
            val next = assertIs<CollaborationNotificationSurfaceState.Ready>(f.owner.states.first())
            assertTrue(next.isIdle)
            assertSame(idle.epoch, next.epoch)
            assertNotEquals(idle.revision, next.revision)
        }
    }

    @Test
    fun pauseHidesWithoutKeysAndSuccessfulCleanupStillNeedsExplicitRetry() = runTest {
        withFixture { f ->
            f.start()
            val first =
                assertIs<CollaborationNotificationSurfaceState.Ready>(f.owner.states.first())
            assertIs<AppResult.Success<*>>(f.show())
            assertIs<AppResult.Success<*>>(f.owner.pause(f.parent))
            assertTrue(f.source.visible.isEmpty())
            assertEquals(CollaborationNotificationSurfaceState.Blocked, f.owner.states.first())
            failed(FailureKind.Unavailable, f.show())
            assertIs<AppResult.Success<*>>(f.owner.reconcile())
            val next = assertIs<CollaborationNotificationSurfaceState.Ready>(f.owner.states.first())
            assertNotSame(first.epoch, next.epoch)
            assertTrue(next.isIdle)
            assertIs<AppResult.Success<*>>(f.show())
        }
    }

    @Test
    fun pauseJoinsPendingPublicationAndCompensationBeforeNamespaceClear() = runTest {
        withFixture { f ->
            f.start()
            f.source.onShow = {
                try {
                    awaitCancellation()
                } finally {
                    f.source.events += "joined-show"
                }
            }
            val work = async { f.show() }
            runCurrent()
            assertIs<AppResult.Success<*>>(f.owner.pause(f.parent))
            failed(FailureKind.Unauthorized, work.await())
            assertTrue(f.source.events.indexOf("joined-show") < f.source.events.indexOf("clear-2"))
            assertEquals(1, f.source.removes)
            assertTrue(f.source.visible.isEmpty())
            assertEquals(CollaborationNotificationSurfaceState.Blocked, f.owner.states.first())
        }
    }

    @Test
    fun failedPauseAndTimedOutCleanupKeepTheGateClosedWithoutAutomaticRetry() = runTest {
        withFixture { f ->
            f.start()
            f.source.onClear = { error("Fictional namespace failure") }
            assertIs<AppResult.Failed>(f.owner.pause(f.parent))
            assertEquals(CollaborationNotificationSurfaceState.Blocked, f.owner.states.first())
            val attempts = f.source.clears
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(attempts, f.source.clears)
            f.source.onClear = { awaitCancellation() }
            failed(FailureKind.Timeout, f.owner.pause(f.parent))
            failed(FailureKind.Unavailable, f.show())
            f.source.onClear = {}
            assertIs<AppResult.Success<*>>(f.owner.reconcile())
            assertIs<AppResult.Success<*>>(f.show())
        }
    }

    @Test
    fun cancellationDuringPauseJoinsCleanupAndCannotReopenTheGate() = runTest {
        withFixture { f ->
            f.start()
            val release = CompletableDeferred<Unit>()
            f.source.onClear = { release.await() }
            val pause = launch { f.owner.pause(f.parent) }
            runCurrent()
            pause.cancel()
            runCurrent()
            assertFalse(pause.isCompleted)
            failed(FailureKind.Unavailable, f.show())
            failed(FailureKind.Unavailable, f.owner.reconcile())
            release.complete(Unit)
            pause.join()
            assertTrue(pause.isCancelled)
            assertEquals(CollaborationNotificationSurfaceState.Blocked, f.owner.states.first())
            f.source.onClear = {}
            assertIs<AppResult.Success<*>>(f.owner.reconcile())
        }
    }

    @Test
    fun oldPauseCannotClearOrReopenAReplacementParent() = runTest {
        withFixture { f ->
            f.start()
            val release = CompletableDeferred<Unit>()
            f.source.onClear = { if (f.source.clears == 2) release.await() }
            val pause = async { f.owner.pause(f.parent) }
            runCurrent()
            val next = f.parent.copy(nativeSessionId = "new-family")
            f.parents.value = next
            runCurrent()
            assertEquals(CollaborationNotificationSurfaceState.Pending, f.owner.states.first())
            release.complete(Unit)
            failed(FailureKind.Unauthorized, pause.await())
            runCurrent()
            val ready =
                assertIs<CollaborationNotificationSurfaceState.Ready>(f.owner.states.first())
            assertEquals(next, ready.parent)
            assertIs<AppResult.Success<*>>(f.owner.withOwner(next) { show(f.payload) })
            val clears = f.source.clears
            failed(FailureKind.Unauthorized, f.owner.pause(f.parent))
            assertEquals(clears, f.source.clears)
            assertEquals(listOf(f.payload.reference), f.source.active())
        }
    }

    @Test
    fun pauseInsideALeaseIsRejectedWithoutCancellingItself() = runTest {
        withFixture { f ->
            f.start()
            assertIs<AppResult.Success<*>>(
                f.owner.withOwner(f.parent) {
                    failed(FailureKind.Validation, f.owner.pause(f.parent))
                    show(f.payload)
                }
            )
            assertEquals(1, f.source.clears)
            assertEquals(listOf(f.payload.reference), f.source.active())
        }
    }

    @Test
    fun failedExplicitRemovalIsCompensatedBeforeAnotherNativeTurn() = runTest {
        withFixture { f ->
            f.start()
            assertIs<AppResult.Success<*>>(f.show())
            f.source.onRemove = {
                if (f.source.removes == 1) error("Fictional first removal failure")
            }
            assertIs<AppResult.Failed>(f.owner.withOwner(f.parent) { remove(f.payload.reference) })
            assertEquals(2, f.source.removes)
            assertTrue(f.source.visible.isEmpty())
            assertIs<AppResult.Success<*>>(f.show())
        }
    }

    @Test
    fun repeatedExplicitRemovalFailureBlocksPublicationUntilExplicitCleanup() = runTest {
        withFixture { f ->
            f.start()
            assertIs<AppResult.Success<*>>(f.show())
            f.source.onRemove = { error("Fictional unavailable removal") }
            assertIs<AppResult.Failed>(f.owner.withOwner(f.parent) { remove(f.payload.reference) })
            assertEquals(2, f.source.removes)
            failed(FailureKind.Unavailable, f.show())
            assertEquals(1, f.source.shows)
            assertIs<AppResult.Success<*>>(f.owner.reconcile())
            assertTrue(f.source.visible.isEmpty())
            f.source.onRemove = {}
            assertIs<AppResult.Success<*>>(f.show())
        }
    }

    @Test
    fun cancellingPendingRemovalJoinsExactCompensationBeforeReturning() = runTest {
        withFixture { f ->
            f.start()
            assertIs<AppResult.Success<*>>(f.show())
            var joined = false
            f.source.onRemove = {
                if (f.source.removes == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        joined = true
                    }
                }
            }
            val pending = launch { f.owner.withOwner(f.parent) { remove(f.payload.reference) } }
            try {
                runCurrent()
                assertEquals(1, f.source.removes)
                pending.cancelAndJoin()
                assertTrue(joined)
                assertEquals(2, f.source.removes)
                assertTrue(f.source.visible.isEmpty())
                assertIs<AppResult.Success<*>>(f.show())
            } finally {
                withContext(NonCancellable) { pending.cancelAndJoin() }
            }
        }
    }

    @Test
    fun uncertainOldRemovalNeverCompensatesByDeletingANewerRender() = runTest {
        withFixture { f ->
            f.start()
            assertIs<AppResult.Success<*>>(f.show())
            val newer = f.payload.copy(reference = f.payload.reference.copy(renderId = "newer"))
            f.source.onRemove = {
                if (f.source.removes == 1) {
                    f.source.visible[newer.reference.id] = newer
                    error("Fictional uncertain replaced removal")
                }
            }
            assertIs<AppResult.Failed>(f.owner.withOwner(f.parent) { remove(f.payload.reference) })
            assertEquals(2, f.source.removes)
            assertEquals(listOf(newer.reference), f.source.active())
        }
    }

    @Test
    fun explicitRemovalSharesTheFourReferenceCleanupBound() = runTest {
        withFixture { f ->
            f.start()
            assertIs<AppResult.Failed>(
                f.owner.withOwner(f.parent) {
                    for (index in 1..4) assertIs<AppResult.Success<*>>(
                        remove(ActionNotificationReference("card-$index", "render"))
                    )
                    failed(
                        FailureKind.Unavailable,
                        remove(ActionNotificationReference("card-5", "render")),
                    )
                    rejection()
                }
            )
            assertEquals(8, f.source.removes)
            assertIs<AppResult.Success<*>>(f.show())
        }
    }

    @Test
    fun startupWaitsForCleanupBeforeGrantingAnyNativeTurn() = runTest {
        withFixture { f ->
            val release = CompletableDeferred<Unit>()
            f.source.onClear = { release.await() }
            f.owner.start()
            runCurrent()
            failed(
                FailureKind.Unavailable,
                f.owner.withOwner<Unit>(f.parent) { error("Not admitted") },
            )
            release.complete(Unit)
            runCurrent()
            assertEquals(AppResult.Success(ActionNotificationSubmission.Submitted), f.show())
            assertEquals(listOf(f.payload.reference), f.source.active())
            f.owner.start()
            runCurrent()
            assertEquals(1, f.source.clears)
        }
    }

    @Test
    fun parentReplacementCancelsAndJoinsPendingNativeIoBeforeNamespaceCleanup() = runTest {
        withFixture { f ->
            f.start()
            f.source.onShow = {
                try {
                    awaitCancellation()
                } finally {
                    f.source.events += "show-stopped"
                }
            }
            val pending = async { f.show() }
            runCurrent()
            assertEquals(1, f.source.visible.size)
            val next = f.parent.copy(nativeSessionId = "next-family")
            f.parents.value = next
            runCurrent()
            failed(FailureKind.Unauthorized, pending.await())
            assertTrue(f.source.events.indexOf("show-stopped") < f.source.events.indexOf("clear-2"))
            assertTrue(f.source.visible.isEmpty())
            failed(
                FailureKind.Unauthorized,
                f.owner.withOwner<Unit>(f.parent) { error("Old family") },
            )
            f.source.onShow = {}
            assertIs<AppResult.Success<*>>(f.owner.withOwner(next) { show(f.payload) })
        }
    }

    @Test
    fun lateNativeCompletionMustJoinBeforeTheReplacementParentCanSubmit() = runTest {
        withFixture { f ->
            f.start()
            val release = CompletableDeferred<Unit>()
            f.source.onShow = { withContext(NonCancellable) { release.await() } }
            val pending = async { f.show() }
            try {
                runCurrent()
                val next = f.parent.copy(nativeSessionId = "replacement-family")
                f.parents.value = next
                runCurrent()
                assertFalse(pending.isCompleted)
                failed(
                    FailureKind.Unavailable,
                    f.owner.withOwner<Unit>(next) { error("Old IPC still active") },
                )
                release.complete(Unit)
                runCurrent()
                failed(FailureKind.Unauthorized, pending.await())
                assertTrue(f.source.visible.isEmpty())
                f.source.onShow = {}
                assertIs<AppResult.Success<*>>(f.owner.withOwner(next) { show(f.payload) })
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun logoutClearsOldCardsAndCleanupCannotGrantANativeTurnWithoutAParent() = runTest {
        withFixture { f ->
            f.start()
            assertIs<AppResult.Success<*>>(f.show())
            f.parents.value = null
            runCurrent()
            assertTrue(f.source.visible.isEmpty())
            failed(
                FailureKind.Unavailable,
                f.owner.withOwner<Unit>(f.parent) { error("Logged out") },
            )
            failed(FailureKind.Unauthorized, f.owner.reconcile())
            val next =
                f.parent.copy(
                    sessionId = "next-local-session",
                    nativeSessionId = "next-native-family",
                )
            f.parents.value = next
            runCurrent()
            failed(
                FailureKind.Unauthorized,
                f.owner.withOwner<Unit>(f.parent) { error("Old local session") },
            )
            assertIs<AppResult.Success<*>>(f.owner.withOwner(next) { show(f.payload) })
        }
    }

    @Test
    fun nativeResultIsWithheldWhenParentChangesBeforeItsObserverConsumesTheChange() = runTest {
        withFixture { f ->
            f.start()
            f.source.onShow = { f.parents.value = f.parent.copy(nativeSessionId = "late-family") }
            failed(FailureKind.Unauthorized, f.show())
            runCurrent()
            assertTrue(f.source.visible.isEmpty())
        }
    }

    @Test
    fun callerCancellationCompensatesOnlyAttemptedRendersAndLeavesTheEpochUsable() = runTest {
        withFixture { f ->
            f.start()
            val unrelated =
                f.payload.copy(reference = ActionNotificationReference("other", "render"))
            f.source.visible[unrelated.reference.id] = unrelated
            f.source.onShow = { awaitCancellation() }
            val pending = async { f.show() }
            runCurrent()
            pending.cancelAndJoin()
            assertEquals(listOf(unrelated.reference), f.source.active())
            assertEquals(1, f.source.clears)
            f.source.onShow = {}
            assertIs<AppResult.Success<*>>(f.show())
        }
    }

    @Test
    fun deadlineJoinsNativeWorkAndRemovesItsUncertainSubmission() = runTest {
        withFixture { f ->
            f.start()
            val stopped = CompletableDeferred<Unit>()
            f.source.onShow = {
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
            val pending = async { f.show() }
            runCurrent()
            advanceTimeBy(10_001)
            runCurrent()
            failed(FailureKind.Timeout, pending.await())
            assertTrue(stopped.isCompleted)
            assertTrue(f.source.visible.isEmpty())
        }
    }

    @Test
    fun anotherRequestDoesNotWaitBehindOrRetainPrivateInputForAnActiveTurn() = runTest {
        withFixture { f ->
            f.start()
            val release = CompletableDeferred<Unit>()
            f.source.onShow = { release.await() }
            val first = async { f.show() }
            runCurrent()
            failed(
                FailureKind.Unavailable,
                f.owner.withOwner<Unit>(f.parent) { error("No waiter") },
            )
            release.complete(Unit)
            assertIs<AppResult.Success<*>>(first.await())
            assertEquals(1, f.source.shows)
        }
    }

    @Test
    fun nativeCallsFromConcurrentChildrenOfOneTurnAreAlsoSerializedWithoutWaiters() = runTest {
        withFixture { f ->
            f.start()
            val release = CompletableDeferred<Unit>()
            f.source.onShow = { release.await() }
            val turn = async {
                f.owner.withOwner(f.parent) {
                    coroutineScope {
                        val first = async(start = CoroutineStart.UNDISPATCHED) { show(f.payload) }
                        failed(
                            FailureKind.Unavailable,
                            show(
                                f.payload.copy(
                                    reference = ActionNotificationReference("second", "render")
                                )
                            ),
                        )
                        release.complete(Unit)
                        first.await()
                    }
                }
            }
            assertIs<AppResult.Success<*>>(turn.await())
            assertEquals(1, f.source.shows)
        }
    }

    @Test
    fun uncertainCleanupFailureClosesTheGateUntilExplicitReconciliationSucceeds() = runTest {
        withFixture { f ->
            f.start()
            f.source.onRemove = { error("Fictional cleanup failure") }
            val result =
                f.owner.withOwner(f.parent) {
                    assertIs<AppResult.Success<*>>(show(f.payload))
                    rejection()
                }
            failed(FailureKind.Unavailable, result)
            failed(
                FailureKind.Unavailable,
                f.owner.withOwner<Unit>(f.parent) { error("Cleanup pending") },
            )
            assertEquals(1, f.source.visible.size)
            f.source.onClear = { error("Fictional OS failure") }
            failed(FailureKind.Unexpected, f.owner.reconcile())
            f.source.onClear = {}
            f.source.onRemove = {}
            assertEquals(AppResult.Success(Unit), f.owner.reconcile())
            assertTrue(f.source.visible.isEmpty())
            assertIs<AppResult.Success<*>>(f.show())
        }
    }

    @Test
    fun aReconciliationCannotReopenAnEpochSupersededWhileItsClearIsPending() = runTest {
        withFixture { f ->
            f.start()
            f.block()
            val release = CompletableDeferred<Unit>()
            var first = true
            f.source.onClear = {
                if (first) {
                    first = false
                    release.await()
                }
            }
            val retry = async { f.owner.reconcile() }
            runCurrent()
            val next = f.parent.copy(nativeSessionId = "new-family")
            f.parents.value = next
            runCurrent()
            release.complete(Unit)
            runCurrent()
            failed(FailureKind.Unauthorized, retry.await())
            failed(
                FailureKind.Unauthorized,
                f.owner.withOwner<Unit>(f.parent) { error("Old parent") },
            )
            assertIs<AppResult.Success<*>>(f.owner.withOwner(next) { show(f.payload) })
        }
    }

    @Test
    fun closingJoinsAnExternalCallerAndClearsTheNamespaceExactlyOnceMore() = runTest {
        withFixture { f ->
            f.start()
            f.source.onShow = { awaitCancellation() }
            val pending = async { f.show() }
            runCurrent()
            f.owner.closeAndJoin()
            assertIs<AppResult.Failed>(pending.await())
            assertTrue(f.source.visible.isEmpty())
            assertEquals(2, f.source.clears)
            f.owner.closeAndJoin()
            assertEquals(2, f.source.clears)
            failed(FailureKind.Unavailable, f.owner.withOwner<Unit>(f.parent) { error("Closed") })
        }
    }

    @Test
    fun aRetainedLeaseCannotPerformIoAfterItsRequestReturns() = runTest {
        withFixture { f ->
            f.start()
            lateinit var retained: CollaborationNotificationSurfaceLease
            assertEquals(
                AppResult.Success(Unit),
                f.owner.withOwner(f.parent) {
                    retained = this
                    AppResult.Success(Unit)
                },
            )
            failed(FailureKind.Unauthorized, retained.show(f.payload))
            failed(FailureKind.Unauthorized, retained.remove(f.payload.reference))
            assertEquals(0, f.source.shows)
            assertEquals(0, f.source.removes)
        }
    }

    @Test
    fun cancellingAfterAReconciliationCommitWithholdsReadinessUntilAnExplicitRetry() = runTest {
        withFixture { f ->
            f.start()
            f.block()
            f.source.onClear = { awaitCancellation() }
            val retry = async { f.owner.reconcile() }
            runCurrent()
            assertTrue(f.source.visible.isEmpty())
            retry.cancelAndJoin()
            failed(
                FailureKind.Unavailable,
                f.owner.withOwner<Unit>(f.parent) { error("Unconfirmed cleanup") },
            )
            f.source.onClear = {}
            assertEquals(AppResult.Success(Unit), f.owner.reconcile())
            assertIs<AppResult.Success<*>>(f.show())
        }
    }

    @Test
    fun failedStartupCleanupHasAFiniteBudgetAndNoAutomaticRetryLoop() = runTest {
        withFixture { f ->
            f.source.onClear = { awaitCancellation() }
            f.owner.start()
            advanceTimeBy(2_001)
            runCurrent()
            failed(
                FailureKind.Unavailable,
                f.owner.withOwner<Unit>(f.parent) { error("No startup grant") },
            )
            advanceTimeBy(50_000)
            runCurrent()
            assertEquals(1, f.source.clears)
            f.source.onClear = {}
            assertEquals(AppResult.Success(Unit), f.owner.reconcile())
            assertIs<AppResult.Success<*>>(f.show())
        }
    }

    @Test
    fun thrownWorkFailureIsContainedAndItsNativeAttemptIsCompensated() = runTest {
        withFixture { f ->
            f.start()
            failed(
                FailureKind.Unexpected,
                f.owner.withOwner<Unit>(f.parent) {
                    assertIs<AppResult.Success<*>>(show(f.payload))
                    error("Fictional failed work")
                },
            )
            assertTrue(f.source.visible.isEmpty())
            assertIs<AppResult.Success<*>>(f.show())
        }
    }

    @Test
    fun oneRequestBoundsItsAttemptedReferenceSetAndRejectsMalformedNativePayloads() = runTest {
        withFixture { f ->
            f.start()
            assertEquals(
                AppResult.Success(Unit),
                f.owner.withOwner(f.parent) {
                    failed(FailureKind.Validation, show(f.payload.copy(body = "x".repeat(401))))
                    for (index in 1..4) assertIs<AppResult.Success<*>>(
                        show(
                            f.payload.copy(
                                reference = ActionNotificationReference("card-$index", "render")
                            )
                        )
                    )
                    failed(FailureKind.Unavailable, show(f.payload))
                    AppResult.Success(Unit)
                },
            )
            assertEquals(4, f.source.shows)
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        try {
            block(f)
        } finally {
            f.source.onClear = {}
            f.source.onRemove = {}
            f.owner.closeAndJoin()
        }
    }

    private class Fixture(private val test: TestScope) {
        val parent =
            CollaborationNativeParent(
                "local-session",
                "https://example.test/api/",
                "account",
                "native-family",
            )
        val parents = MutableStateFlow<CollaborationNativeParent?>(parent)
        val source = Source()
        val owner =
            CollaborationNotificationSurfaceOwner(
                parents,
                source,
                StandardTestDispatcher(test.testScheduler),
            )
        val payload =
            ActionNotificationPayload(
                ActionNotificationReference("card", "render"),
                "Sender",
                "Body",
                ActionNotificationKind.entries.associateWith { "opaque-${it.key}" },
                true,
            )

        fun start() {
            owner.start()
            test.runCurrent()
        }

        suspend fun show() = owner.withOwner(parent) { show(payload) }

        suspend fun block() {
            source.onRemove = { error("Fictional cleanup failure") }
            assertIs<AppResult.Failed>(
                owner.withOwner(parent) {
                    show(payload)
                    rejection()
                }
            )
            source.onRemove = {}
        }
    }

    private class Source : ActionNotificationDisplaySource {
        val visible = linkedMapOf<String, ActionNotificationPayload>()
        val events = mutableListOf<String>()
        var shows = 0
        var removes = 0
        var clears = 0
        var onShow: suspend () -> Unit = {}
        var onRemove: suspend () -> Unit = {}
        var onClear: suspend () -> Unit = {}

        override suspend fun show(
            payload: ActionNotificationPayload
        ): ActionNotificationSubmission {
            shows++
            visible[payload.reference.id] = payload
            onShow()
            return ActionNotificationSubmission.Submitted
        }

        override suspend fun active() = visible.values.map { it.reference }

        override suspend fun remove(
            expected: ActionNotificationReference
        ): ActionNotificationRemoval {
            removes++
            onRemove()
            val found = visible[expected.id] ?: return ActionNotificationRemoval.Absent
            if (found.reference != expected) return ActionNotificationRemoval.Replaced
            visible.remove(expected.id)
            return ActionNotificationRemoval.Removed
        }

        override suspend fun clear() {
            clears++
            events += "clear-$clears"
            visible.clear()
            onClear()
        }
    }

    companion object {
        private fun failed(kind: FailureKind, result: AppResult<*>) =
            assertEquals(kind, assertIs<AppResult.Failed>(result).failure.kind)

        private fun rejection() =
            AppResult.Failed(Failure(FailureKind.Unavailable, "Fictional pending work"))
    }
}
