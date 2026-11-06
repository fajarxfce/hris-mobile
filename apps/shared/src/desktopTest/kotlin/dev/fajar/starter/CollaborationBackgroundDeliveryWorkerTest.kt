package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.fixtures.NativeUiServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.koin.dsl.module

class CollaborationBackgroundDeliveryWorkerTest {
    @Test
    fun capturedDeliveryRestoresOriginalsAfterRestartWithoutReadingOrChangingSelection() =
        runBlocking {
            withFixture { f ->
                val original = f.queue()
                f.reopen()
                f.withoutSelection()
                f.sdk.backgroundEnabled = true
                val grant = f.grant()
                assertEquals(SyncResult.Complete, f.worker(grant))
                assertEquals(listOf(original.intent.id), f.sdk.enqueued)
                assertEquals(1, f.sdk.deliveries)
                assertEquals(0, f.sdk.restores)
                assertEquals(1, f.sdk.backgroundAcquisitions)
                assertEquals(1, f.sdk.backgroundPublications)
                assertEquals(1, f.sdk.backgroundCloses)
                assertEquals(f.sdk.childOpens, f.sdk.childCloses)
                assertEquals(
                    AppResult.Success(null),
                    f.koin.get<ReadCollaborationDeliveryWork>()(
                        CollaborationLocalScope.Captured(grant.owner.localListAccess())
                    ),
                )
                assertEquals(SyncResult.Complete, f.worker(f.grant()))
                assertEquals(1, f.sdk.backgroundAcquisitions)
                assertTrue(f.server.unexpectedPaths.isEmpty())
            }
        }

    @Test
    fun newSetupReadmissionAllowsBackgroundRecoveryOfAFormerlyDeniedOriginal() = runBlocking {
        withFixture { f ->
            val original = f.queue()
            val grant = f.grant()
            val lists = f.koin.get<CollaborationListRepository>()
            assertIs<AppResult.Success<*>>(lists.restrict(grant.owner.localListAccess(), denied()))
            f.withoutSelection()
            f.sdk.backgroundEnabled = true
            assertEquals(SyncResult.Complete, f.worker(grant))
            assertEquals(listOf(original.intent.id), f.sdk.enqueued)
            assertNull(
                assertIs<AppResult.Success<CollaborationListAdmission>>(
                        lists.admission(grant.owner.localListAccess())
                    )
                    .value
                    .denial
            )
            assertEquals(1, f.sdk.backgroundCloses)
        }
    }

    @Test
    fun lostBackgroundRoomReplyReplaysTheSameOperationAndKeepsOneMessage() = runBlocking {
        withFixture { f ->
            val original = f.queue()
            f.server.collaboration.rooms[f.first] = "NOT_REQUESTED"
            f.server.collaboration.loseRoomReply = true
            f.withoutSelection()
            f.sdk.backgroundEnabled = true
            assertIs<SyncResult.Retry>(f.worker(f.grant()))
            assertEquals(1, f.sdk.backgroundCloses)
            assertEquals(SyncResult.Complete, f.worker(f.grant()))
            assertEquals(2, f.server.collaboration.roomRequests.size)
            assertEquals(
                f.server.collaboration.roomRequests.first(),
                f.server.collaboration.roomRequests.last(),
            )
            assertEquals(listOf(original.intent.id), f.sdk.enqueued)
            assertEquals(2, f.sdk.backgroundCloses)
        }
    }

    @Test
    fun backgroundHistoryCapacityDefersRecoveryWithoutReclaimingForegroundOrResending() =
        runBlocking {
            withFixture { f ->
                val original = f.queue()
                f.withoutSelection()
                f.sdk.backgroundEnabled = true
                f.sdk.historyCapacityOnce = true
                val first = assertIs<SyncResult.Retry>(f.worker(f.grant()))
                assertEquals("client.collaboration_history_focus_capacity", first.failure?.code)
                assertEquals(0, f.sdk.historyReopens)
                assertEquals(0, f.sdk.restores)
                assertEquals(1, f.sdk.deliveries)
                assertEquals(SyncResult.Complete, f.worker(f.grant()))
                assertEquals(listOf(original.intent.id), f.sdk.enqueued)
                assertEquals(1, f.sdk.deliveries)
                assertEquals(2, f.sdk.backgroundCloses)
            }
        }

    @Test
    fun sdkInterruptionOrCompanyDenialStopsPendingSendAndReleasesOnlyTheBorrowedReference() =
        runBlocking {
            for (company in listOf(false, true)) withFixture { f ->
                val original = f.queue()
                val grant = f.grant()
                f.withoutSelection()
                f.sdk.backgroundEnabled = true
                val entered = CompletableDeferred<Unit>()
                f.sdk.beforeResume = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val attempt = async { f.worker(grant) }
                withTimeout(5.seconds) { entered.await() }
                val previouslyClosedQueues = f.sdk.queueCloses
                if (company)
                    assertIs<AppResult.Success<*>>(
                        f.koin
                            .get<CollaborationListRepository>()
                            .restrict(grant.owner.localListAccess(), denied())
                    )
                else
                    f.sdk.backgroundInterruptions.value =
                        Failure(FailureKind.Network, "Connection stopped")
                val result = withTimeout(5.seconds) { attempt.await() }
                if (company) assertIs<SyncResult.Blocked>(result)
                else assertIs<SyncResult.Retry>(result)
                assertEquals(1, f.sdk.backgroundCloses)
                assertEquals(f.sdk.childOpens, f.sdk.childCloses)
                assertEquals(previouslyClosedQueues + 1, f.sdk.queueCloses)
                assertEquals(listOf(original.intent.id), f.sdk.enqueued)
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                            f.koin
                                .get<CollaborationMessageRepository>()
                                .get(grant.owner.localListAccess(), original.intent.id)
                        )
                        .value
                )
            }
        }

    @Test
    fun nativeParentLossWhileSendIsPendingCancelsThePassWithoutErasingIntent() = runBlocking {
        withFixture { f ->
            val original = f.queue()
            f.withoutSelection()
            f.sdk.backgroundEnabled = true
            val entered = CompletableDeferred<Unit>()
            f.sdk.beforeResume = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val attempt = async { f.worker(f.grant()) }
            withTimeout(5.seconds) { entered.await() }
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().clear(f.session.id),
            )
            assertIs<SyncResult.Blocked>(withTimeout(5.seconds) { attempt.await() })
            assertEquals(1, f.sdk.backgroundCloses)
            assertEquals(listOf(original.intent.id), f.sdk.enqueued)
        }
    }

    @Test
    fun concurrentSelectedOrBackgroundInvocationDoesNotJoinAWaitingQueue() = runBlocking {
        withFixture { f ->
            f.queue()
            f.withoutSelection()
            f.sdk.backgroundEnabled = true
            val entered = CompletableDeferred<Unit>()
            f.sdk.beforeEnter = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val first = async { f.worker(f.grant()) }
            withTimeout(5.seconds) { entered.await() }
            for (background in listOf(false, true)) {
                val busy =
                    withTimeout(1.seconds) {
                        assertIs<SyncResult.Retry>(
                            if (background) f.worker(f.grant()) else f.worker()
                        )
                    }
                assertEquals("client.collaboration_delivery_busy", busy.failure?.code)
            }
            first.cancelAndJoin()
            assertEquals(1, f.sdk.backgroundAcquisitions)
            assertEquals(1, f.sdk.backgroundCloses)
        }
    }

    @Test
    fun aCancelledLateBackgroundAcquisitionIsReleasedWithoutPublicationOrSend() = runBlocking {
        withFixture { f ->
            f.queue()
            f.withoutSelection()
            f.sdk.backgroundEnabled = true
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.sdk.beforeBackgroundReturn = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val attempt = async { f.worker(f.grant()) }
            withTimeout(5.seconds) { entered.await() }
            attempt.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { attempt.await() }
            attempt.join()
            assertEquals(1, f.sdk.backgroundCloses)
            assertEquals(0, f.sdk.backgroundPublications)
            assertEquals(0, f.sdk.childOpens)
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun originalAdmissionDeadlineIncludesPendingNativeAcquisition() = runBlocking {
        withFixture { f ->
            f.queue()
            f.withoutSelection()
            f.sdk.backgroundEnabled = true
            val entered = CompletableDeferred<Unit>()
            f.sdk.beforeBackgroundReturn = {
                entered.complete(Unit)
                awaitCancellation()
            }
            // Keep the two-second admission clock still while real SQLite/HTTP setup runs.
            // The worker timeout is then advanced explicitly, independently of machine pauses.
            val elapsed = TestTimeSource()
            val scheduler = TestCoroutineScheduler()
            val grant = f.grant().copy(monotonicDeadline = elapsed.markNow() + 2.seconds)
            val attempt = async(StandardTestDispatcher(scheduler)) { f.worker(grant) }
            try {
                withTimeout(5.seconds) {
                    while (!entered.isCompleted) {
                        scheduler.runCurrent()
                        if (!entered.isCompleted) delay(1)
                    }
                }
                scheduler.advanceTimeBy(1_999)
                scheduler.runCurrent()
                assertFalse(attempt.isCompleted)
                elapsed += 2.seconds
                scheduler.advanceTimeBy(1)
                scheduler.runCurrent()
                assertTrue(attempt.isCompleted)
                val result = assertIs<SyncResult.Retry>(attempt.await())
                assertEquals("client.collaboration_delivery_timeout", result.failure?.code)
                assertEquals(1, f.sdk.backgroundCloses)
                assertEquals(0, f.sdk.backgroundPublications)
                assertEquals(0, f.sdk.childOpens)
            } finally {
                attempt.cancel()
                withContext(NonCancellable) {
                    withTimeout(5.seconds) {
                        while (!attempt.isCompleted) {
                            scheduler.runCurrent()
                            if (!attempt.isCompleted) delay(1)
                        }
                    }
                    attempt.join()
                }
            }
        }
    }

    @Test
    fun expiredMonotonicAdmissionDoesNoSetupOrNativeWork() = runBlocking {
        withFixture { f ->
            f.queue()
            val elapsed = TestTimeSource()
            val grant = f.grant().copy(monotonicDeadline = elapsed.markNow() + 2.seconds)
            elapsed += 2.seconds
            f.withoutSelection()
            val before = f.server.collaboration.reads.size
            assertIs<SyncResult.Retry>(f.worker(grant))
            assertEquals(before, f.server.collaboration.reads.size)
            assertEquals(0, f.sdk.backgroundAcquisitions)
            assertEquals(0, f.sdk.restores)
        }
    }

    @Test
    fun releasingABackgroundPassPreservesAnAlreadyRetainedForegroundRuntime() = runBlocking {
        withFixture { f ->
            f.queue()
            val grant = f.grant()
            assertIs<AppResult.Success<*>>(
                f.koin.get<EnsureCollaborationRuntime>()(
                    f.workspaceId,
                    f.server.collaboration.deviceId,
                )
            )
            assertEquals(1, f.sdk.restores)
            f.withoutSelection()
            f.sdk.backgroundEnabled = true
            assertEquals(SyncResult.Complete, f.worker(grant))
            assertEquals(
                AppResult.Success(true),
                f.sdk.isOpen(grant.owner, f.server.collaboration.deviceId),
            )
            assertEquals(1, f.sdk.restores)
            assertEquals(1, f.sdk.backgroundCloses)
        }
    }

    private suspend fun withFixture(block: suspend (CollaborationWorkerFixture) -> Unit) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            block(f)
        } finally {
            f.close()
        }
    }
}

private fun CollaborationWorkerFixture.grant(
    duration: Duration = 60.seconds
): CollaborationBackgroundAdmission {
    val now = Clock.System.now()
    val owner =
        CollaborationOwner(
            session.id,
            session.binding.audience,
            session.user.id,
            NativeUiServer.COMPANY_ONE,
            session.binding.serverId,
        )
    return CollaborationBackgroundAdmission(
        CollaborationAdmissionObservation(
            owner,
            server.collaboration.deviceId,
            0,
            0,
            0,
            now,
            now + duration,
        ),
        now,
        now + duration,
    )
}

private fun CollaborationWorkerFixture.withoutSelection() {
    val selected = koin.get<WorkspaceRepository>()
    val noSelection =
        object : WorkspaceRepository by selected {
            override suspend fun current(sessionId: String): Nothing =
                error("No selected workspace reads")

            override fun observe(sessionId: String): Nothing =
                error("No selected workspace observers")
        }
    koin.loadModules(listOf(module { single<WorkspaceRepository> { noSelection } }))
}

private fun denied() =
    Failure(FailureKind.AccessDenied, "Company access denied", code = "company_access_denied")
