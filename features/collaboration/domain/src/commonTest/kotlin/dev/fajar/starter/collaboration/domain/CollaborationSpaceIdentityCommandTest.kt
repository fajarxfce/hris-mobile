@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationSpaceIdentityCommandTest {
    @Test
    fun pendingPreparationTimesOutAndLostInsertResponseRecoversTheSavedOperation() = runTest {
        val waiting = SpaceIdentityCommandFixture()
        val firstDiscovery = waiting.discovery()
        waiting.beforeCurrent = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(waiting.prepare()(firstDiscovery)).failure.kind,
        )
        assertEquals(0, waiting.inserts)
        assertTrue(waiting.sent.isEmpty())

        val lost = SpaceIdentityCommandFixture()
        val discovery = lost.discovery()
        lost.beforeInsert = { currentCoroutineContext().cancel() }
        val pending = async { lost.prepare()(discovery) }
        assertFailsWith<CancellationException> { pending.await() }
        val retained = assertNotNull(lost.saved())
        assertIs<CollaborationSpaceIdentityOutcome.Pending>(retained.outcome)
        lost.beforeInsert = {}
        assertEquals(AppResult.Success(retained), lost.prepare()(discovery))
        assertEquals(1, lost.inserts)
        assertTrue(lost.sent.isEmpty())
    }

    @Test
    fun denialReceivedAtCancellationStillClosesReadWhenItsPersistenceFails() = runTest {
        val f = SpaceIdentityCommandFixture()
        val original = f.prepared()
        f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
        f.beforeSelect = {
            currentCoroutineContext().cancel()
            AppResult.Failed(f.base.denied)
        }
        val pending = async { f.delivery()(f.owner, original.command.id) }
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(1, f.base.restrictions)
        assertEquals(
            f.base.denied,
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.base.gate.value)
                .value
                .denial,
        )
        assertIs<CollaborationSpaceIdentityOutcome.Unconfirmed>(assertNotNull(f.saved()).outcome)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun repeatedPreparationAndLostResponseKeepOneImmutableOperation() = runTest {
        val f = SpaceIdentityCommandFixture()
        val discovery = f.discovery()
        val original =
            assertIs<AppResult.Success<CollaborationSpaceIdentityCommandRecord>>(
                    f.prepare()(discovery)
                )
                .value
        assertEquals(AppResult.Success(original), f.prepare()(discovery))
        assertEquals(1, f.inserts)
        assertTrue(f.sent.isEmpty())
        f.beforeSelect = { AppResult.Failed(f.base.unavailable) }
        assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
        val retained = assertNotNull(f.saved())
        assertEquals(original.command, retained.command)
        assertIs<CollaborationSpaceIdentityOutcome.Unconfirmed>(retained.outcome)
        f.beforeSelect = {
            AppResult.Success(CollaborationSetupReceipt(f.owner.parent.accountId, 0))
        }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceIdentityCommandRecord>>(
                    f.delivery()(f.owner, original.command.id)
                )
                .value
        assertEquals(2, result.attempts)
        assertIs<CollaborationSpaceIdentityOutcome.Selected>(result.outcome)
        assertEquals(listOf(original.command, original.command), f.sent)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun failedOrRejectedUncertaintyWriteNeverStartsHttp() = runTest {
        for (fails in listOf(false, true)) {
            val f = SpaceIdentityCommandFixture()
            val original = f.prepared()
            f.beforeUpdate = {
                if (fails) AppResult.Failed(f.base.storageFailure) else AppResult.Success(false)
            }
            assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
            assertTrue(f.sent.isEmpty())
            assertEquals(original, f.saved())
        }
    }

    @Test
    fun cancellationDuringTransportAndLateCancelledSuccessRetainUnconfirmedWork() = runTest {
        for (late in listOf(false, true)) {
            val f = SpaceIdentityCommandFixture()
            val original = f.prepared()
            val entered = CompletableDeferred<Unit>()
            f.beforeSelect = {
                entered.complete(Unit)
                if (late) {
                    currentCoroutineContext().cancel()
                    AppResult.Success(CollaborationSetupReceipt(f.owner.parent.accountId, 0))
                } else awaitCancellation()
            }
            val pending = async { f.delivery()(f.owner, original.command.id) }
            entered.await()
            if (late) assertFailsWith<CancellationException> { pending.await() }
            else pending.cancelAndJoin()
            val retained = assertNotNull(f.saved())
            assertEquals(original.command, retained.command)
            assertIs<CollaborationSpaceIdentityOutcome.Unconfirmed>(retained.outcome)
            assertEquals(1, retained.attempts)
        }
    }

    @Test
    fun deniedDeliveryAndFailedPersistenceRemainDeniedThroughAnUnavailableRetry() = runTest {
        val f = SpaceIdentityCommandFixture()
        val original = f.prepared()
        f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
        f.beforeUpdate = { outcome ->
            if (outcome is CollaborationSpaceIdentityOutcome.Unconfirmed && outcome.failure != null)
                AppResult.Failed(f.base.storageFailure)
            else AppResult.Success(true)
        }
        f.beforeSelect = { AppResult.Failed(f.base.denied) }
        assertEquals(
            f.base.denied,
            assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id)).failure,
        )
        assertEquals(1, f.base.restrictions)
        f.beforeSelect = { AppResult.Failed(f.base.unavailable) }
        assertEquals(
            f.base.denied,
            assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id)).failure,
        )
        assertEquals(
            f.base.denied,
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.base.gate.value)
                .value
                .denial,
        )
        assertEquals(original.command, assertNotNull(f.saved()).command)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun receiptSuccessDoesNotReopenADeniedDirectory() = runTest {
        val f = SpaceIdentityCommandFixture()
        val original = f.prepared()
        f.base.deny()
        val selected =
            assertIs<AppResult.Success<CollaborationSpaceIdentityCommandRecord>>(
                    f.delivery()(f.owner, original.command.id)
                )
                .value
        assertIs<CollaborationSpaceIdentityOutcome.Selected>(selected.outcome)
        assertEquals(
            f.base.denied,
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.base.gate.value)
                .value
                .denial,
        )
        assertEquals(0, f.base.admits)
    }

    @Test
    fun staleAttemptsStopAndExplicitReauthenticatedRetryPreservesOriginalIntent() = runTest {
        for (during in listOf(false, true)) {
            val f = SpaceIdentityCommandFixture()
            val original = f.prepared()
            val newSession =
                f.native.session.copy(
                    id = "fresh-local-login",
                    binding = f.native.session.binding.copy(serverId = f.owner.spaceId),
                )
            if (during)
                f.beforeSelect = {
                    f.session(newSession)
                    AppResult.Success(CollaborationSetupReceipt(f.owner.parent.accountId, 0))
                }
            else f.session(newSession)
            assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
            assertEquals(if (during) 1 else 0, f.sent.size)
            assertEquals(original.command, assertNotNull(f.saved()).command)
            assertIs<AppResult.Failed>(f.observe()(f.access).first())
            val newOwner =
                f.owner.copy(
                    parent =
                        f.owner.parent.copy(
                            sessionId = newSession.id,
                            nativeSessionId = f.owner.spaceId,
                        )
                )
            assertEquals(
                AppResult.Success(f.saved()),
                f.observe()(newOwner.localDirectoryAccess()).first(),
            )
            f.beforeSelect = {
                AppResult.Success(CollaborationSetupReceipt(f.owner.parent.accountId, 0))
            }
            val selected =
                assertIs<AppResult.Success<CollaborationSpaceIdentityCommandRecord>>(
                        f.delivery()(newOwner, original.command.id)
                    )
                    .value
            assertIs<CollaborationSpaceIdentityOutcome.Selected>(selected.outcome)
            assertEquals(original.command, selected.command)
            assertEquals(List(if (during) 2 else 1) { original.command }, f.sent)
            assertEquals(0, f.base.admits)
        }
    }

    @Test
    fun clientUpgradeReplaysTheSameOperationAndSelectedFactsRemainReadableOffline() = runTest {
        val f = SpaceIdentityCommandFixture()
        val original = f.prepared()
        assertIs<AppResult.Failed>(
            f.delivery(f.build.copy(number = 1_000_000_000L))(f.owner, original.command.id)
        )
        assertTrue(f.sent.isEmpty())
        assertEquals(original, f.saved())
        val upgraded = f.build.copy(number = 6)
        val selected =
            assertIs<AppResult.Success<CollaborationSpaceIdentityCommandRecord>>(
                    f.delivery(upgraded)(f.owner, original.command.id)
                )
                .value
        assertEquals(original.command, selected.command)
        assertEquals(5L, selected.command.clientBuild)
        f.native.setup.now += 365.days
        val newSession =
            f.native.session.copy(
                id = "new-login",
                binding = f.native.session.binding.copy(serverId = f.owner.spaceId),
            )
        f.session(newSession)
        val newOwner =
            f.owner.copy(
                parent =
                    f.owner.parent.copy(
                        sessionId = newSession.id,
                        nativeSessionId = f.owner.spaceId,
                    )
            )
        assertEquals(
            AppResult.Success(selected),
            f.observe()(newOwner.localDirectoryAccess()).first(),
        )
        assertEquals(
            AppResult.Success(selected),
            f.delivery(upgraded)(newOwner, original.command.id),
        )
        assertEquals(1, f.sent.size)
    }

    @Test
    fun originalHardDeadlineWallRollbackAndMonotonicExpiryStopPublication() = runTest {
        for (mode in 0..2) {
            val f = SpaceIdentityCommandFixture()
            val original = f.prepared()
            if (mode == 0) {
                f.session(
                    f.native.session.copy(
                        binding =
                            f.native.session.binding.copy(
                                expiresAtEpochMillis =
                                    (f.native.setup.now + 1.seconds).toEpochMilliseconds()
                            )
                    )
                )
                var reads = 0
                f.beforeCurrent = {
                    if (reads++ == 0) {
                        f.native.setup.now += 2.seconds
                        f.session(
                            f.native.session.copy(
                                binding =
                                    f.native.session.binding.copy(
                                        expiresAtEpochMillis =
                                            (f.native.setup.now + 60.seconds).toEpochMilliseconds()
                                    )
                            )
                        )
                    }
                }
            } else
                f.beforeSelect = {
                    if (mode == 1) f.native.setup.now -= 1.seconds else f.native.time += 16.seconds
                    AppResult.Success(CollaborationSetupReceipt(f.owner.parent.accountId, 0))
                }
            assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id))
            assertEquals(if (mode == 0) 0 else 1, f.sent.size)
            assertFalse(
                assertNotNull(f.saved()).outcome is CollaborationSpaceIdentityOutcome.Selected
            )
        }
    }

    @Test
    fun concurrentExplicitCallsSerializeAndReuseTheConfirmedResult() = runTest {
        val f = SpaceIdentityCommandFixture()
        val original = f.prepared()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeSelect = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(CollaborationSetupReceipt(f.owner.parent.accountId, 0))
        }
        val replay = f.delivery()
        val first = async { replay(f.owner, original.command.id) }
        entered.await()
        val second = async { replay(f.owner, original.command.id) }
        runCurrent()
        assertEquals(1, f.sent.size)
        release.complete(Unit)
        assertIs<AppResult.Success<CollaborationSpaceIdentityCommandRecord>>(first.await())
        assertEquals(first.await(), second.await())
        assertEquals(1, f.sent.size)
    }

    @Test
    fun mutexWaitIsBoundedAndNonCooperativeLateTransportCannotPublishSuccess() = runTest {
        val f = SpaceIdentityCommandFixture()
        val original = f.prepared()
        val entered = CompletableDeferred<Unit>()
        f.beforeSelect = {
            entered.complete(Unit)
            withContext(NonCancellable) { delay(20_000) }
            AppResult.Success(CollaborationSetupReceipt(f.owner.parent.accountId, 0))
        }
        val replay = f.delivery()
        val first = async { replay(f.owner, original.command.id) }
        entered.await()
        val second = async { replay(f.owner, original.command.id) }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(second.await()).failure.kind)
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(first.await()).failure.kind)
        assertEquals(1, f.sent.size)
        assertIs<CollaborationSpaceIdentityOutcome.Unconfirmed>(assertNotNull(f.saved()).outcome)
    }

    @Test
    fun accountReplacementCancelsQueuedCommandFactsAndDisposalClosesObservation() = runTest {
        val f = SpaceIdentityCommandFixture()
        f.prepared()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val values = mutableListOf<AppResult<CollaborationSpaceIdentityCommandRecord?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).collect {
                    values += it
                    if (values.size == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            }
        entered.await()
        f.records.value = AppResult.Success(null)
        runCurrent()
        f.session(f.native.session.copy(user = f.native.session.user.copy(id = f.owner.spaceId)))
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(2, values.size)
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        job.cancelAndJoin()
        assertEquals(1, f.stops)
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun expiredPreparationAndInvalidReceiptCannotCreateAConfirmedIdentity() = runTest {
        val f = SpaceIdentityCommandFixture()
        val discovery = f.discovery()
        f.native.time += 61.seconds
        assertIs<AppResult.Failed>(f.prepare()(discovery))
        assertEquals(0, f.inserts)
        val original = f.prepared()
        f.beforeSelect = { AppResult.Success(CollaborationSetupReceipt(f.owner.spaceId, 0)) }
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.delivery()(f.owner, original.command.id)).failure.kind,
        )
        assertIs<CollaborationSpaceIdentityOutcome.Unconfirmed>(assertNotNull(f.saved()).outcome)
    }
}
