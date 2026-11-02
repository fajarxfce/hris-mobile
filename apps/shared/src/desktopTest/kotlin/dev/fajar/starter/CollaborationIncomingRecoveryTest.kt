package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationIncomingRecovery
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
import org.koin.dsl.module

class CollaborationIncomingRecoveryTest {
    @Test
    fun actualCompanyBindingAcquiresIncomingAndDeliversOutgoingInTheSameTurn() = runBlocking {
        withFixture { f ->
            val incoming = f.sdk.incoming(f.first, "Fictional incoming before send")
            val outgoing = f.queue(text = "Fictional outgoing reply")
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            assertEquals(
                SyncResult.Retry(),
                f.koin.get<dev.fajar.starter.app.work.CollaborationCompanyRecovery>()(
                    f.incomingGrant()
                ),
            )
            assertEquals(incoming.text, f.incomingOriginal(incoming)?.text)
            assertEquals(listOf(outgoing.intent.id), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
            assertEquals(2, f.sdk.backgroundAcquisitions)
            assertReleased(f)
        }
    }

    @Test
    fun incomingWithoutOutboxOrSelectionPersistsOriginalsAcrossContainerRestart() = runBlocking {
        withFixture { f ->
            val original = f.sdk.incoming(f.first, "Fictional incoming message")
            f.reopen()
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            val worker = f.koin.get<CollaborationIncomingRecovery>()
            assertEquals(SyncResult.Retry(), worker(f.incomingGrant()))
            assertEquals(original.text, f.incomingOriginal(original)?.text)
            assertEquals(f.first, f.position()?.afterConversationId)
            assertNull(f.position()?.inFlightConversationId)
            assertEquals(SyncResult.Retry(), worker(f.incomingGrant()))
            assertEquals(SyncResult.Complete, worker(f.incomingGrant()))
            assertEquals(CollaborationRoomRecoveryPosition(), f.position())
            assertEquals(3, f.sdk.backgroundAcquisitions)
            assertReleased(f)
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
            assertEquals(0, f.sdk.restores)
            f.reopen()
            assertEquals(original.text, f.incomingOriginal(original)?.text)
            assertEquals(CollaborationRoomRecoveryPosition(), f.position())
            assertTrue(f.server.unexpectedPaths.isEmpty())
        }
    }

    @Test
    fun aFailedFirstRoomDoesNotStarveLaterRoomsOrLoseItsFailureAtDirectoryEnd() = runBlocking {
        withFixture { f ->
            val original = f.sdk.incoming(f.first, "Fictional retained original")
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            f.sdk.historyCapacityOnce = true
            val worker = f.koin.get<CollaborationIncomingRecovery>()
            assertEquals(SyncResult.Retry(), worker(f.incomingGrant()))
            assertTrue(f.position()!!.retryRequired)
            assertNull(f.incomingOriginal(original))
            assertEquals(SyncResult.Retry(), worker(f.incomingGrant()))
            assertNotNull(assertIs<SyncResult.Retry>(worker(f.incomingGrant())).failure)
            assertEquals(
                f.server.collaboration.ids.map(f.server.collaboration::roomId),
                f.sdk.attemptedRooms,
            )
            assertEquals(SyncResult.Retry(), worker(f.incomingGrant()))
            assertEquals(original.text, f.incomingOriginal(original)?.text)
            assertEquals(SyncResult.Retry(), worker(f.incomingGrant()))
            assertEquals(SyncResult.Complete, worker(f.incomingGrant()))
            assertEquals(0, f.sdk.historyReopens)
            assertEquals(0, f.sdk.deliveries)
            assertReleased(f)
        }
    }

    @Test
    fun anEmptyDirectoryCompletesWithoutOpeningTheSdk() = runBlocking {
        withFixture { f ->
            f.server.collaboration.listedIds = emptyList()
            f.withoutIncomingSelection()
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            assertEquals(CollaborationRoomRecoveryPosition(), f.position())
            assertEquals(0, f.sdk.backgroundAcquisitions)
        }
    }

    @Test
    fun unprovisionedRoomsNeverTriggerCreationAndPendingProvisioningRetainsRetry() = runBlocking {
        for (state in listOf("NOT_REQUESTED", "QUEUED", "RUNNING", "FAILED", "CANCELLED")) {
            withFixture { f ->
                f.server.collaboration.listedIds = listOf(f.first)
                f.server.collaboration.rooms[f.first] = state
                f.withoutIncomingSelection()
                val result = f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant())
                if (state == "QUEUED" || state == "RUNNING")
                    assertNotNull(assertIs<SyncResult.Retry>(result).failure)
                else assertEquals(SyncResult.Complete, result)
                assertTrue(f.server.collaboration.roomRequests.isEmpty())
                assertEquals(0, f.sdk.backgroundAcquisitions)
            }
        }
    }

    @Test
    fun freshSetupReadmissionReopensPreviouslyDeniedIncomingProjection() = runBlocking {
        withFixture { f ->
            val original = f.sdk.incoming(f.first, "Fictional readmitted message")
            val access = f.incomingGrant().owner.localListAccess()
            assertIs<AppResult.Success<*>>(
                f.koin.get<CollaborationListRepository>().restrict(access, denied())
            )
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            assertEquals(
                SyncResult.Retry(),
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            assertEquals(original.text, f.incomingOriginal(original)?.text)
            assertNull(
                assertIs<AppResult.Success<CollaborationListAdmission>>(
                        f.koin.get<CollaborationListRepository>().admission(access)
                    )
                    .value
                    .denial
            )
            assertReleased(f)
        }
    }

    @Test
    fun pendingCancellationRetainsAttemptAndReplacementContinuesLaterRoomsBeforeRetry() =
        runBlocking {
            withFixture { f ->
                val original = f.sdk.incoming(f.first, "Fictional interrupted original")
                f.withoutIncomingSelection()
                f.sdk.backgroundEnabled = true
                val entered = CompletableDeferred<Unit>()
                f.sdk.beforeHistory = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val job = async { f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()) }
                withTimeout(5.seconds) { entered.await() }
                job.cancelAndJoin()
                assertEquals(f.first, f.position()?.inFlightConversationId)
                assertNull(f.incomingOriginal(original))
                assertReleased(f)
                f.sdk.beforeHistory = {}
                f.reopen()
                f.withoutIncomingSelection()
                val replacement = f.koin.get<CollaborationIncomingRecovery>()
                assertEquals(SyncResult.Retry(), replacement(f.incomingGrant()))
                assertNotNull(assertIs<SyncResult.Retry>(replacement(f.incomingGrant())).failure)
                assertEquals(SyncResult.Retry(), replacement(f.incomingGrant()))
                assertEquals(original.text, f.incomingOriginal(original)?.text)
                assertEquals(SyncResult.Retry(), replacement(f.incomingGrant()))
                assertEquals(SyncResult.Complete, replacement(f.incomingGrant()))
                assertReleased(f)
            }
        }

    @Test
    fun concurrentIncomingDoesNotQueueAnotherRuntimeAndCancellationReleasesTheLock() = runBlocking {
        withFixture { f ->
            f.sdk.incoming(f.first, "Fictional pending original")
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            val worker = f.koin.get<CollaborationIncomingRecovery>()
            val entered = CompletableDeferred<Unit>()
            f.sdk.beforeHistory = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = async { worker(f.incomingGrant()) }
            withTimeout(5.seconds) { entered.await() }
            val busy =
                assertIs<SyncResult.Retry>(withTimeout(1.seconds) { worker(f.incomingGrant()) })
            assertEquals("client.collaboration_incoming_busy", busy.failure?.code)
            assertEquals(1, f.sdk.backgroundAcquisitions)
            job.cancelAndJoin()
            f.sdk.beforeHistory = {}
            assertEquals(SyncResult.Retry(), worker(f.incomingGrant()))
            assertEquals(2, f.sdk.backgroundAcquisitions)
            assertReleased(f)
        }
    }

    @Test
    fun sdkInterruptionCancelsHistoryAndClosesOwnedObserversAndChildren() = runBlocking {
        withFixture { f ->
            val original = f.sdk.incoming(f.first, "Fictional interrupted history")
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            val entered = CompletableDeferred<Unit>()
            f.sdk.beforeHistory = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = async { f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()) }
            withTimeout(5.seconds) { entered.await() }
            f.sdk.backgroundInterruptions.value = Failure(FailureKind.Network, "Interrupted")
            assertEquals(SyncResult.Retry(), withTimeout(5.seconds) { job.await() })
            assertTrue(f.position()!!.retryRequired)
            assertNull(f.incomingOriginal(original))
            assertReleased(f)
        }
    }

    @Test
    fun lateHistoryAfterAccountLossCannotPublishOriginalsOrClaimCoverage() = runBlocking {
        withFixture { f ->
            val original = f.sdk.incoming(f.first, "Fictional late original")
            val access = f.incomingGrant().owner.localListAccess()
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.sdk.beforeHistory = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val job = async { f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()) }
            withTimeout(5.seconds) { entered.await() }
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().clear(f.session.id),
            )
            release.complete(Unit)
            assertIs<SyncResult.Blocked>(withTimeout(5.seconds) { job.await() })
            assertEquals(
                AppResult.Success(null),
                f.koin
                    .get<CollaborationHistoryRepository>()
                    .getByEvent(
                        access,
                        f.first,
                        f.server.collaboration.roomId(f.first),
                        original.eventId,
                    ),
            )
            assertReleased(f)
        }
    }

    @Test
    fun lateHistoryAfterCompanyDenialRetainsTheDeniedAttemptWithoutPublishingContent() =
        runBlocking {
            withFixture { f ->
                val original = f.sdk.incoming(f.first, "Fictional restricted original")
                val access = f.incomingGrant().owner.localListAccess()
                f.withoutIncomingSelection()
                f.sdk.backgroundEnabled = true
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.sdk.beforeHistory = {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                val job = async { f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()) }
                withTimeout(5.seconds) { entered.await() }
                assertIs<AppResult.Success<*>>(
                    f.koin.get<CollaborationListRepository>().restrict(access, denied())
                )
                release.complete(Unit)
                assertIs<SyncResult.Blocked>(withTimeout(5.seconds) { job.await() })
                assertIs<AppResult.Failed>(f.koin.get<ReadCollaborationRoomRecovery>()(access))
                assertNull(f.incomingOriginal(original))
                assertReleased(f)
            }
        }

    @Test
    fun lateRuntimeAfterCancellationIsClosedBeforeAnyTimelinePublication() = runBlocking {
        withFixture { f ->
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.sdk.beforeBackgroundReturn = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val job = async { f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()) }
            withTimeout(5.seconds) { entered.await() }
            job.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { job.await() }
            job.join()
            assertEquals(f.first, f.position()?.inFlightConversationId)
            assertEquals(0, f.sdk.backgroundPublications)
            assertEquals(0, f.sdk.childOpens)
            assertReleased(f)
        }
    }

    @Test
    fun originalAdmissionDeadlineBoundsPendingHistoryWithoutRenewal() = runBlocking {
        withFixture { f ->
            f.sdk.incoming(f.first, "Fictional timed original")
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            val entered = CompletableDeferred<Unit>()
            f.sdk.beforeHistory = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = async {
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant(2.seconds))
            }
            withTimeout(5.seconds) { entered.await() }
            val result = assertIs<SyncResult.Retry>(withTimeout(5.seconds) { job.await() })
            assertEquals("client.collaboration_incoming_timeout", result.failure?.code)
            assertEquals(f.first, f.position()?.inFlightConversationId)
            assertReleased(f)
        }
    }

    @Test
    fun expiredOrAlreadyCancelledEntryDoesNoSetupOrRuntimeWork() = runBlocking {
        withFixture { f ->
            f.withoutIncomingSelection()
            val elapsed = TestTimeSource()
            val grant = f.incomingGrant().copy(monotonicDeadline = elapsed.markNow() + 2.seconds)
            elapsed += 2.seconds
            val worker = f.koin.get<CollaborationIncomingRecovery>()
            val reads = f.server.collaboration.reads.size
            assertIs<SyncResult.Retry>(worker(grant))
            val job = Job().apply { cancel() }
            assertFailsWith<CancellationException> {
                withContext(job) { worker(f.incomingGrant()) }
            }
            assertEquals(reads, f.server.collaboration.reads.size)
            assertEquals(0, f.sdk.backgroundAcquisitions)
        }
    }

    private fun assertReleased(f: CollaborationWorkerFixture) {
        assertEquals(f.sdk.backgroundAcquisitions, f.sdk.backgroundCloses)
        assertEquals(f.sdk.childOpens, f.sdk.childCloses)
        assertEquals(0, f.sdk.backgroundInterruptions.subscriptionCount.value)
        assertEquals(0, f.sdk.backgroundCredentialFailures.subscriptionCount.value)
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

internal fun CollaborationWorkerFixture.incomingGrant(
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

internal fun CollaborationWorkerFixture.withoutIncomingSelection() {
    val selected = koin.get<WorkspaceRepository>()
    val unavailable =
        object : WorkspaceRepository by selected {
            override suspend fun current(sessionId: String): Nothing =
                error("No selected workspace reads")

            override fun observe(sessionId: String): Nothing =
                error("No selected workspace observers")
        }
    koin.loadModules(listOf(module { single<WorkspaceRepository> { unavailable } }))
}

private suspend fun CollaborationWorkerFixture.position() =
    assertIs<AppResult.Success<CollaborationRoomRecoverySnapshot>>(
            koin.get<ReadCollaborationRoomRecovery>()(incomingGrant().owner.localListAccess())
        )
        .value
        .progress
        ?.position

internal suspend fun CollaborationWorkerFixture.incomingOriginal(
    event: CollaborationHistoryChange.Text
) =
    assertIs<AppResult.Success<CollaborationHistoryMessage?>>(
            koin
                .get<CollaborationHistoryRepository>()
                .getByEvent(
                    incomingGrant().owner.localListAccess(),
                    first,
                    server.collaboration.roomId(first),
                    event.eventId,
                )
        )
        .value

private fun denied() =
    Failure(FailureKind.AccessDenied, "Company access denied", code = "company_access_denied")
