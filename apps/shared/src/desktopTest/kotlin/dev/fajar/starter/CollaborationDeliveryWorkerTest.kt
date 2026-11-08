package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationComposerOutboxRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationDraftRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CollaborationDeliveryWorkerTest {
    @Test
    fun composerOriginalsRecoverAfterRestartWithoutClearingTheNewerEditor() = runBlocking {
        withFixture { f ->
            val access =
                assertIs<AppResult.Success<CollaborationLocalAccess>>(
                        f.koin.get<ObserveCollaborationAccess>()(f.workspaceId).first()
                    )
                    .value
            val submit = f.koin.get<SubmitCollaborationComposer>()
            val now = Clock.System.now()
            val first =
                CollaborationComposerSubmission(
                    CollaborationDraft(
                        f.first,
                        "00000000-0000-0000-0000-000000091001",
                        "First composer original",
                        now,
                    ),
                    "00000000-0000-0000-0000-000000092001",
                    now,
                )
            val second =
                CollaborationComposerSubmission(
                    CollaborationDraft(
                        f.first,
                        "00000000-0000-0000-0000-000000091002",
                        "Second composer original",
                        now,
                    ),
                    "00000000-0000-0000-0000-000000092002",
                    now + kotlin.time.Duration.parse("1s"),
                )
            assertEquals(AppResult.Success(first), submit(f.workspaceId, null, first, access))
            assertEquals(
                AppResult.Success(second),
                submit(f.workspaceId, first.nextDraft, second, access),
            )
            val next =
                second.nextDraft.copy(
                    revision = "00000000-0000-0000-0000-000000093001",
                    text = "Keep typing after the two sends",
                )
            assertEquals(
                AppResult.Success(true),
                f.koin.get<CollaborationDraftRepository>().replace(access, second.nextDraft, next),
            )
            assertTrue(f.sdk.enqueued.isEmpty())
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(listOf(first.id, second.id), f.sdk.enqueued)
            assertEquals(
                AppResult.Success(next),
                f.koin.get<CollaborationDraftRepository>().get(access, f.first),
            )
            assertTrue(
                assertIs<AppResult.Success<CollaborationComposerSubmissionPage>>(
                        f.koin.get<CollaborationComposerOutboxRepository>().pending(access)
                    )
                    .value
                    .submissions
                    .isEmpty()
            )
            // The exact authenticated archive proves replay after the outbox has gone away.
            assertEquals(
                AppResult.Success(first),
                f.koin.get<SubmitCollaborationComposer>()(f.workspaceId, null, first, access),
            )
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(listOf(first.id, second.id), f.sdk.enqueued)
            assertEquals(
                AppResult.Success(next),
                f.koin.get<CollaborationDraftRepository>().get(access, f.first),
            )
        }
    }

    @Test
    fun heldComposerInAnUnavailableConversationDoesNotBlockAnExistingMessage() = runBlocking {
        withFixture { f ->
            val queued = f.queue(text = "Independent existing command")
            val access =
                assertIs<AppResult.Success<CollaborationLocalAccess>>(
                        f.koin.get<ObserveCollaborationAccess>()(f.workspaceId).first()
                    )
                    .value
            val now = Clock.System.now()
            val unavailable =
                CollaborationComposerSubmission(
                    CollaborationDraft(
                        "00000000-0000-0000-0000-000000094001",
                        "00000000-0000-0000-0000-000000094002",
                        "Retain for review",
                        now,
                    ),
                    "00000000-0000-0000-0000-000000094003",
                    now,
                )
            // Represents a previously admitted local send whose conversation metadata is absent.
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.koin
                    .get<CollaborationComposerOutboxRepository>()
                    .handoff(access, unavailable, null),
            )
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(listOf(queued.intent.id), f.sdk.enqueued)
            assertEquals(
                AppResult.Success(listOf(CollaborationComposerOutboxEntry(unavailable, true))),
                f.koin.get<CollaborationComposerOutboxRepository>().observe(access).first(),
            )
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(1, f.sdk.deliveries)
        }
    }

    @Test
    fun expiredOwnedAuthorityRetriesOfflineAndThenDeliversTheOriginalAfterRestart() = runBlocking {
        for (workspaceExpired in listOf(false, true)) withFixture { f ->
            val queued = f.queue(text = "Original offline recovery message")
            val repo = f.koin.get<WorkspaceRepository>()
            val before =
                assertNotNull(
                    assertIs<AppResult.Success<Workspace?>>(repo.current(f.session.id)).value
                )
            val now = Clock.System.now()
            val expired =
                if (workspaceExpired)
                    before.copy(
                        admission =
                            before.admission.copy(
                                checkedAt = now - 2.hours,
                                expiresAt = now - 1.hours,
                            )
                    )
                else
                    before.copy(
                        policy = requireNotNull(before.policy).copy(requestedAt = now - 2.minutes)
                    )
            assertEquals(AppResult.Success(true), repo.compareAndSet(f.session.id, before, expired))
            f.reopen()
            f.server.companyPolicy.offline = true
            val reads = f.server.companyPolicy.reads.get()
            val offline = assertIs<SyncResult.Retry>(f.worker())
            assertEquals(FailureKind.Network, offline.failure?.kind)
            assertEquals(
                reads + 1,
                f.server.companyPolicy.reads.get(),
                "Only one authority renewal per pass",
            )
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
            val pending =
                assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                        f.koin.get<ObserveCollaborationMessages>()(f.workspaceId, f.first).first()
                    )
                    .value
            assertEquals(listOf(queued.intent.id), pending.map { it.intent.id })
            f.server.companyPolicy.offline = false
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(listOf(queued.intent.id), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(1, f.sdk.deliveries)
        }
    }

    @Test
    fun renewedModuleDenialRetainsOriginalWorkWithoutOpeningTheSdk() = runBlocking {
        withFixture { f ->
            val queued = f.queue()
            val access =
                assertIs<AppResult.Success<CollaborationLocalAccess>>(
                        f.koin.get<ObserveCollaborationAccess>()(f.workspaceId).first()
                    )
                    .value
            val repo = f.koin.get<WorkspaceRepository>()
            val before =
                assertNotNull(
                    assertIs<AppResult.Success<Workspace?>>(repo.current(f.session.id)).value
                )
            val expired =
                before.copy(
                    policy =
                        requireNotNull(before.policy)
                            .copy(requestedAt = Clock.System.now() - 2.minutes)
                )
            assertEquals(AppResult.Success(true), repo.compareAndSet(f.session.id, before, expired))
            f.server.companyPolicy.disabledModules = setOf("COLLABORATION")
            val denied = assertIs<SyncResult.Blocked>(f.worker())
            assertEquals("company_module_disabled", denied.failure.code)
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
            val retained =
                assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                        f.koin.get<CollaborationMessageRepository>().observe(access, null).first()
                    )
                    .value
            assertEquals(listOf(queued.intent.id), retained.map { it.intent.id })
        }
    }

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
    fun recoveredNativeAcceptanceDoesNotLeaveAnUnmappedQueueBlockingTheNextMessage() = runBlocking {
        withFixture { f ->
            val first = f.queue(text = "First retained message")
            f.sdk.loseDeliveryReply = true
            f.sdk.retainQueueAfterLostReply = true
            assertIs<SyncResult.Retry>(f.worker())
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker(), "Recover the accepted first message")
            val next = f.queue(text = "Next retained message")
            assertEquals(SyncResult.Complete, f.worker(), "Deliver the next message after recovery")
            assertEquals(listOf(first.intent.id, next.intent.id), f.sdk.enqueued)
            assertEquals(1, f.sdk.settlements)
            assertEquals(2, f.sdk.deliveries)
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(1, f.sdk.settlements)
            assertEquals(2, f.sdk.deliveries)
            assertEquals(f.sdk.childOpens, f.sdk.childCloses)
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
    fun anOlderConfirmedMessageCannotHideARecentLostAcknowledgementOutsideItsWindow() =
        runBlocking {
            withFixture { f ->
                val older = f.queue(text = "Older confirmation awaiting original history")
                f.sdk.withholdOriginals = true
                assertIs<SyncResult.Retry>(f.worker())
                val stored =
                    assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                            f.koin.get<ObserveCollaborationMessages>()(f.workspaceId, f.first)
                                .first()
                        )
                        .value
                        .single()
                val confirmed = assertIs<CollaborationMessageState.Sent>(stored.state)
                f.sdk.withholdOriginals = false
                f.sdk.withheldHistory += confirmed.eventId
                f.sdk.historyWindowLimit = 4
                repeat(12) { f.sdk.incoming(f.first, "Intervening message $it") }
                val recent = f.queue(text = "Recent message with a lost acknowledgement")
                f.sdk.loseDeliveryReply = true
                assertIs<SyncResult.Retry>(f.worker())
                assertEquals(2, f.sdk.deliveries)

                // Both durable commands survive process/container replacement. Recovery must
                // find the new head while the unrelated old original remains unavailable.
                f.reopen()
                assertIs<SyncResult.Retry>(f.worker())
                val remaining =
                    assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                            f.koin.get<ObserveCollaborationMessages>()(f.workspaceId, f.first)
                                .first()
                        )
                        .value
                assertEquals(listOf(older.intent.id), remaining.map { it.intent.id })
                assertEquals(listOf(older.intent.id, recent.intent.id), f.sdk.enqueued)
                assertEquals(2, f.sdk.deliveries, "Recover the ACK without another resume")
                val history =
                    assertIs<AppResult.Success<CollaborationHistoryPage>>(
                            f.koin.get<ObserveCollaborationHistory>()(f.workspaceId, f.first)
                                .first()
                        )
                        .value
                assertEquals(1, history.messages.count { it.logicalId == recent.intent.id })
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
    fun ownerStartupRequestsOnlyRegisteredTaskKeysAndDoesNotRunNetworkDeliveryItself() =
        runBlocking {
            withFixture { f ->
                f.queue()
                f.scheduled.clear()
                val lifetime = f.koin.get<ApplicationCollaborationLifetime>()
                lifetime.start()
                withTimeout(5_000) {
                    while (QueueCollaborationText.DELIVERY_KEY !in f.scheduled) yield()
                }
                assertEquals(
                    listOf(CollaborationRecoveryWake.WORK_KEY, QueueCollaborationText.DELIVERY_KEY),
                    f.scheduled,
                )
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationRecoveryWake?>>(
                            f.koin.get<ReadCollaborationRecoveryWake>()()
                        )
                        .value
                )
                assertEquals(0, f.sdk.deliveries)
                assertEquals(0, f.sdk.backgroundAcquisitions)
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
    fun platformTaskAndForegroundRequestShareOneWorkerWithoutWaitingForAnotherPass() = runBlocking {
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
            assertTrue(second.isCompleted)
            assertEquals(
                "client.collaboration_delivery_busy",
                assertIs<SyncResult.Retry>(second.await()).failure?.code,
            )
            assertEquals(1, f.sdk.deliveries)
            release.complete(Unit)
            withTimeout(5_000) {
                assertEquals(SyncResult.Complete, first.await())
                assertEquals(SyncResult.Complete, platformTask())
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
