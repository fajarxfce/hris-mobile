package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CollaborationDeliveryWorkerTest {
    @Test
    fun storedIntentResumesAfterContainerRestartAndIsRetiredOnlyWithOriginalEvidence() =
        runBlocking {
            withFixture { f ->
                val queued = f.queue(text = "  exact offline body\n  ")
                assertEquals(listOf(QueueCollaborationText.DELIVERY_KEY), f.scheduled)
                f.reopen()
                assertEquals(SyncResult.Complete, f.worker())
                assertEquals(listOf(queued.intent.id), f.sdk.enqueued)
                assertEquals(1, f.sdk.deliveries)
                val page =
                    assertIs<AppResult.Success<CollaborationHistoryPage>>(
                            f.koin.get<ObserveCollaborationHistory>()(f.workspaceId, f.first)
                                .first()
                        )
                        .value
                assertEquals(queued.intent.text, page.messages.single().text)
                assertEquals(SyncResult.Complete, f.worker())
                assertEquals(1, f.sdk.deliveries)
                assertEquals(f.sdk.childOpens, f.sdk.childCloses)
                assertTrue(f.server.unexpectedPaths.isEmpty())
            }
        }

    @Test
    fun missingNativeAckIsRecoveredBeforeAnySecondEnqueueOrResume() = runBlocking {
        withFixture { f ->
            val message = f.queue()
            f.sdk.loseDeliveryReply = true
            assertIs<SyncResult.Retry>(f.worker())
            assertEquals(1, f.sdk.deliveries)
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(listOf(message.intent.id), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
            assertEquals(f.sdk.childOpens, f.sdk.childCloses)
        }
    }

    @Test
    fun absentOriginalHistoryBacksOffAndNeverReopensConfirmedOnlyDelivery() = runBlocking {
        withFixture { f ->
            f.queue()
            f.sdk.withholdOriginals = true
            val waiting = assertIs<SyncResult.Retry>(f.worker())
            assertEquals("client.collaboration_history_pending", waiting.failure?.code)
            assertIs<SyncResult.Retry>(f.worker())
            assertEquals(1, f.sdk.deliveries)
            f.sdk.withholdOriginals = false
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(1, f.sdk.deliveries)
        }
    }

    @Test
    fun failedRoomDoesNotStarveAnotherConversationAndNoFailedProvisioningIsRetried() = runBlocking {
        withFixture { f ->
            val second = f.server.collaboration.ids[1]
            f.queue(f.first)
            val deliverable = f.queue(second)
            f.server.collaboration.rooms[f.first] = "FAILED"
            assertIs<SyncResult.Blocked>(f.worker())
            assertEquals(listOf(deliverable.intent.id), f.sdk.enqueued)
            assertTrue(f.server.collaboration.roomRequests.isEmpty())
            val work =
                assertIs<AppResult.Success<CollaborationDeliveryWork?>>(
                        f.koin.get<ReadCollaborationDeliveryWork>()()
                    )
                    .value!!
            assertEquals(second, work.cursor?.afterConversationId)
            assertEquals(listOf(f.first), work.conversationIds)
        }
    }

    @Test
    fun lostRoomReplyReplaysItsSavedOperationBeforeMessageDelivery() = runBlocking {
        withFixture { f ->
            f.server.collaboration.rooms[f.first] = "NOT_REQUESTED"
            f.server.collaboration.loseRoomReply = true
            f.queue()
            assertIs<SyncResult.Retry>(f.worker())
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker())
            val calls = f.server.collaboration.roomRequests
            assertEquals(2, calls.size)
            assertEquals(calls.first(), calls.last())
            assertEquals(1, f.sdk.enqueued.size)
        }
    }

    @Test
    fun focusCapacityReopensWithoutSendingTheMessageAgain() = runBlocking {
        withFixture { f ->
            f.queue()
            f.sdk.historyCapacityOnce = true
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(1, f.sdk.historyReopens)
            assertEquals(1, f.sdk.enqueued.size)
            assertEquals(1, f.sdk.deliveries)
        }
    }

    @Test
    fun ownerStartupRequestsOnlyTheTaskKeyAndDoesNotRunNetworkDeliveryItself() = runBlocking {
        withFixture { f ->
            f.queue()
            f.scheduled.clear()
            val lifetime = f.koin.get<ApplicationCollaborationLifetime>()
            lifetime.start()
            withTimeout(5_000) { while (f.scheduled.isEmpty()) yield() }
            assertEquals(listOf(QueueCollaborationText.DELIVERY_KEY), f.scheduled)
            assertEquals(0, f.sdk.deliveries)
            lifetime.closeAndJoin()
        }
    }

    @Test
    fun cancelledLateChildIsClosedAndPersistedTurnLetsAnotherRoomRunAfterRestart() = runBlocking {
        withFixture { f ->
            val second = f.server.collaboration.ids[1]
            f.queue()
            val next = f.queue(second)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.sdk.beforeEnter = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val attempt = async { f.worker() }
            withTimeout(5_000) { entered.await() }
            attempt.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { attempt.await() }
            attempt.join()
            assertEquals(1, f.sdk.childCloses)
            assertTrue(f.sdk.enqueued.isEmpty())
            f.sdk.beforeEnter = {}
            f.reopen()
            val work =
                assertIs<AppResult.Success<CollaborationDeliveryWork?>>(
                        f.koin.get<ReadCollaborationDeliveryWork>()()
                    )
                    .value!!
            assertEquals(listOf(second, f.first), work.conversationIds)
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(next.intent.id, f.sdk.enqueued.first())
            assertEquals(f.sdk.childOpens, f.sdk.childCloses)
        }
    }

    @Test
    fun ownerLossWhileAcquiringChildClosesItBeforeAnyNativeSend() = runBlocking {
        withFixture { f ->
            f.queue()
            f.sdk.beforeEnter = {
                assertEquals(
                    AppResult.Success(true),
                    f.koin
                        .get<dev.fajar.starter.identity.domain.repositories.SessionRepository>()
                        .clear(f.session.id),
                )
            }
            assertIs<SyncResult.Blocked>(f.worker())
            assertEquals(1, f.sdk.childCloses)
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
            assertEquals(SyncResult.Complete, f.worker())
        }
    }

    @Test
    fun missingDeviceRequiresExplicitSetupAndKeepsTheQueuedIntent() = runBlocking {
        withFixture { f ->
            val queued = f.queue()
            f.server.collaboration.activeDevice = false
            val result = assertIs<SyncResult.Blocked>(f.worker())
            assertEquals("client.collaboration_setup_required", result.failure.code)
            assertEquals(0, f.sdk.restores)
            assertTrue(f.server.collaboration.roomRequests.isEmpty())
            val records =
                assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                        f.koin.get<ObserveCollaborationMessages>()(f.workspaceId, f.first).first()
                    )
                    .value
            assertEquals(listOf(queued), records)
        }
    }

    @Test
    fun blockedRoomDoesNotStopRemainingBatchesInAnotherRoom() = runBlocking {
        withFixture { f ->
            val second = f.server.collaboration.ids[1]
            f.queue()
            val next = (1..9).map { f.queue(second, "Fictional batch $it") }
            f.server.collaboration.rooms[f.first] = "FAILED"
            assertEquals(SyncResult.Retry(), f.worker())
            assertEquals(8, f.sdk.enqueued.size)
            assertIs<SyncResult.Blocked>(f.worker())
            assertEquals(next.map { it.intent.id }.toSet(), f.sdk.enqueued.toSet())
            assertEquals(9, f.sdk.enqueued.size)
            val work =
                assertIs<AppResult.Success<CollaborationDeliveryWork?>>(
                        f.koin.get<ReadCollaborationDeliveryWork>()()
                    )
                    .value!!
            assertEquals(listOf(f.first), work.conversationIds)
            assertTrue(f.server.collaboration.roomRequests.isEmpty())
        }
    }

    @Test
    fun platformTaskAndForegroundRequestShareOneSerializedWorker() = runBlocking {
        withFixture { f ->
            val queued = f.queue()
            val platformTask =
                f.koin.getAll<dev.fajar.starter.sync.domain.SyncTask>().single {
                    it.key == QueueCollaborationText.DELIVERY_KEY
                }
            assertSame(f.worker, platformTask)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.sdk.beforeResume = {
                entered.complete(Unit)
                release.await()
            }
            val first = async { f.worker() }
            withTimeout(5_000) { entered.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { platformTask() }
            assertFalse(second.isCompleted)
            assertEquals(1, f.sdk.deliveries)
            release.complete(Unit)
            withTimeout(5_000) {
                assertEquals(SyncResult.Complete, first.await())
                assertEquals(SyncResult.Complete, second.await())
            }
            assertEquals(listOf(queued.intent.id), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
            assertEquals(f.sdk.childOpens, f.sdk.childCloses)
        }
    }

    @Test
    fun lostAckAndFullHistoryFocusRecoverFromLatestHeadBeforeAnotherSend() = runBlocking {
        withFixture { f ->
            val queued = f.queue()
            f.sdk.loseDeliveryReply = true
            assertIs<SyncResult.Retry>(f.worker())
            val records =
                assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                        f.koin.get<ObserveCollaborationMessages>()(f.workspaceId, f.first).first()
                    )
                    .value
            assertIs<CollaborationMessageState.Staged>(records.single().state)
            f.reopen()
            f.sdk.historyCapacityOnce = true
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(1, f.sdk.historyReopens)
            assertEquals(listOf(queued.intent.id), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
            assertEquals(f.sdk.childOpens, f.sdk.childCloses)
        }
    }

    @Test
    fun openingOrClosingRuntimeBacksOffBeforeAnyNativeSendAndRecoversOnNextPass() = runBlocking {
        for (code in
            listOf("client.collaboration_runtime_opening", "client.collaboration_close_pending")) {
            withFixture { f ->
                val queued = f.queue()
                f.sdk.restoreFailure =
                    Failure(FailureKind.Unavailable, "Fixture runtime busy", code = code)
                val result = assertIs<SyncResult.Retry>(f.worker())
                assertEquals(code, result.failure?.code)
                assertTrue(f.sdk.enqueued.isEmpty())
                f.sdk.restoreFailure = null
                assertEquals(SyncResult.Complete, f.worker())
                assertEquals(listOf(queued.intent.id), f.sdk.enqueued)
                assertEquals(1, f.sdk.deliveries)
            }
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
