package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.StageCollaborationText
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class StageCollaborationTextTest {
    @Test
    fun oldRetainedTransactionDoesNotBlockANewMessageAfterLegacyCredentialReplacement() = runTest {
        val f = StageFixture()
        f.prepare()
        val old =
            f.record.copy(
                intent = f.record.intent.copy(id = stageId(999), text = "Older message"),
                revision = 2,
                state =
                    CollaborationMessageState.Staged(
                        f.device.copy(bootstrapOperationId = stageId(998)),
                        "older",
                        f.origin,
                    ),
            )
        f.base.records.value += old
        f.nativeIds += "older"
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals(setOf("older", "original"), f.nativeIds)
        assertEquals(1, f.enqueues)
        assertEquals(old, f.base.records.value.last())
        assertEquals(
            "original",
            assertIs<CollaborationMessageState.Staged>(f.record.state).transactionId,
        )
    }

    @Test
    fun cancelledOriginalNeverAcquiresTheNativeQueue() = runTest {
        val f = StageFixture()
        f.prepare()
        f.replace(f.record.copy(state = CollaborationMessageState.Cancelled))
        assertEquals(AppResult.Success(f.record), f.stage())
        assertEquals(0, f.acquisitions)
        assertEquals(0, f.preparations)
        assertEquals(0, f.enqueues)
    }

    @Test
    fun cancellationWinningDuringPreparationPreventsNativeEnqueue() = runTest {
        val f = StageFixture()
        f.prepare()
        f.afterPrepare = { f.replace(f.record.copy(state = CollaborationMessageState.Cancelled)) }
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(CollaborationMessageState.Cancelled, f.record.state)
        assertEquals(0, f.enqueues)
        assertEquals(1, f.closes)
    }

    @Test
    fun preparationIsRetainedBeforeEnqueueAndSurvivesMappingRecovery() = runTest {
        val f = StageFixture()
        f.prepare()
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals(f.origin, f.record.state.origin)
        assertEquals(1, f.preparations)
        assertEquals(1, f.enqueues)
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals(1, f.preparations)
        assertEquals(1, f.enqueues)
    }

    @Test
    fun quotedIntentReachesPreparationAndEnqueueExactlyOnceAfterMappingRecovery() = runTest {
        val f = StageFixture()
        f.prepare(replyToEventId = "\$quoted-original")
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals("\$quoted-original", f.record.intent.replyToEventId)
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals(1, f.preparations)
        assertEquals(1, f.enqueues)
    }

    @Test
    fun invalidOrFailedPreparationNeverWritesAnAttemptOrEnqueues() = runTest {
        for (scenario in 0..2) {
            val f = StageFixture()
            f.prepare()
            when (scenario) {
                0 -> f.origin = f.origin.copy(sender = "invalid")
                1 -> f.origin = f.origin.copy(contentHash = "bad")
                else -> f.prepareFailure = f.base.storage
            }
            assertIs<AppResult.Failed>(f.stage())
            assertEquals(CollaborationMessageState.Queued, f.record.state)
            assertEquals(0, f.enqueues)
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun ownerReplacementWhilePreparingCannotPublishNativeWork() = runTest {
        val f = StageFixture()
        f.prepare()
        f.afterPrepare = { f.base.setup.session = null }
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(CollaborationMessageState.Queued, f.record.state)
        assertEquals(0, f.enqueues)
        assertEquals(1, f.closes)
    }

    @Test
    fun cancellationWhilePreparingReleasesTheWriterWithoutStaging() = runTest {
        val f = StageFixture()
        f.prepare()
        val entered = CompletableDeferred<Unit>()
        f.afterPrepare = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.stage() }
        entered.await()
        task.cancelAndJoin()
        assertEquals(CollaborationMessageState.Queued, f.record.state)
        assertEquals(0, f.enqueues)
        assertEquals(1, f.closes)
    }

    @Test
    fun failedSnapshotIsNeverInterpretedAsAnEmptyQueue() = runTest {
        val f = StageFixture()
        f.prepare()
        f.snapshotFailure = f.base.storage
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(0, f.enqueues)
        assertEquals(CollaborationMessageState.Queued, f.record.state)
        f.snapshotFailure = null
        f.afterEnqueue = { f.snapshotFailure = f.base.storage }
        assertIs<AppResult.Failed>(f.stage())
        assertIs<CollaborationMessageState.Staging>(f.record.state)
        f.snapshotFailure = null
        f.afterEnqueue = {}
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals(1, f.enqueues)
    }

    @Test
    fun rejectedMappingCasRetainsTheSameNativeAttempt() = runTest {
        val f = StageFixture()
        f.prepare()
        f.rejectMapping = true
        assertIs<AppResult.Failed>(f.stage())
        assertIs<CollaborationMessageState.Staging>(f.record.state)
        f.rejectMapping = false
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals(1, f.enqueues)
    }

    @Test
    fun denialAfterNativeCommitPreservesUncertaintyUntilAdmittedRecovery() = runTest {
        val f = StageFixture()
        f.prepare()
        f.afterEnqueue = { f.base.gate.value = CollaborationListAdmission("denied", f.base.denied) }
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.stage()).failure)
        assertIs<CollaborationMessageState.Staging>(f.record.state)
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.stage()).failure)
        f.base.gate.value = CollaborationListAdmission("admitted", null)
        f.afterEnqueue = {}
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals(1, f.enqueues)
    }

    @Test
    fun accountReplacementWhileAdmissionReadCannotEnqueue() = runTest {
        val f = StageFixture()
        f.prepare()
        f.base.beforeAdmission = {
            if (f.record.state is CollaborationMessageState.Staging) f.base.setup.session = null
        }
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(0, f.enqueues)
        assertIs<CollaborationMessageState.Staging>(f.record.state)
        assertEquals(1, f.closes)
    }

    @Test
    fun nativeEnqueueFollowsDurableUncertaintyAndPublishesOnlyTheOriginalMapping() = runTest {
        val f = StageFixture()
        f.prepare()
        val staged = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.stage()).value
        assertEquals(CollaborationMessageState.Staged(f.device, "original", f.origin), staged.state)
        assertEquals(listOf("save-staging", "enqueue", "save-staged", "close"), f.events)
        assertEquals(AppResult.Success(staged), f.stage())
        assertEquals(1, f.enqueues)
        assertEquals(1, f.acquisitions)
    }

    @Test
    fun nativeCommitBeforeLostResponseIsReconciledWithoutAnotherEnqueue() = runTest {
        val f = StageFixture()
        f.prepare()
        f.enqueueResult = AppResult.Failed(f.base.storage)
        assertIs<AppResult.Failed>(f.stage())
        assertIs<CollaborationMessageState.Staging>(f.record.state)
        f.enqueueResult = AppResult.Success(Unit)
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals(1, f.enqueues)
        assertEquals(
            "original",
            assertIs<CollaborationMessageState.Staged>(f.record.state).transactionId,
        )
    }

    @Test
    fun lostMappingWriteAndFailedLocalPreparationCannotGenerateReplacementTransaction() = runTest {
        val f = StageFixture()
        f.prepare()
        f.failMapping = true
        assertIs<AppResult.Failed>(f.stage())
        assertIs<CollaborationMessageState.Staging>(f.record.state)
        f.failMapping = false
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals(1, f.enqueues)
        val g = StageFixture()
        g.prepare()
        g.failStaging = true
        assertIs<AppResult.Failed>(g.stage())
        assertEquals(0, g.enqueues)
        assertEquals(CollaborationMessageState.Queued, g.record.state)
    }

    @Test
    fun cancellationAfterNativeCommitRetainsIntentAndClosesOnlyItsWriter() = runTest {
        val f = StageFixture()
        f.prepare()
        val committed = CompletableDeferred<Unit>()
        f.afterEnqueue = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val job = launch { f.stage() }
        committed.await()
        job.cancelAndJoin()
        assertEquals(1, f.closes)
        assertIs<CollaborationMessageState.Staging>(f.record.state)
        f.afterEnqueue = {}
        assertIs<AppResult.Success<*>>(f.stage())
        assertEquals(1, f.enqueues)
    }

    @Test
    fun unknownNativeQueueAndDifferentDeviceUncertaintyStayHeld() = runTest {
        val f = StageFixture()
        f.prepare()
        f.nativeIds += "unknown"
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(0, f.enqueues)
        assertEquals(CollaborationMessageState.Queued, f.record.state)
        val g = StageFixture()
        g.prepare()
        g.replace(
            g.record.copy(
                revision = 1,
                state =
                    CollaborationMessageState.Staging(
                        g.device.copy(bootstrapOperationId = stageId(77)),
                        emptySet(),
                    ),
            )
        )
        assertEquals(
            "client.collaboration_queue_other_device",
            assertIs<AppResult.Failed>(g.stage()).failure.code,
        )
        assertEquals(0, g.enqueues)
    }

    @Test
    fun uncertainSiblingAndMultipleNewTransactionsCannotBeAttributedToThisIntent() = runTest {
        val f = StageFixture()
        f.prepare()
        f.base.records.value +=
            f.record.copy(
                intent = f.record.intent.copy(id = stageId(99)),
                revision = 1,
                state = CollaborationMessageState.Staging(f.device, emptySet()),
            )
        assertEquals(
            "client.collaboration_queue_unresolved",
            assertIs<AppResult.Failed>(f.stage()).failure.code,
        )
        assertEquals(0, f.enqueues)
        val g = StageFixture()
        g.prepare()
        g.replace(
            g.record.copy(
                revision = 1,
                state = CollaborationMessageState.Staging(g.device, emptySet()),
            )
        )
        g.nativeIds += setOf("unknown-a", "unknown-b")
        assertIs<AppResult.Failed>(g.stage())
        assertEquals(0, g.enqueues)
    }

    @Test
    fun expiredAdmissionAndUnavailableDeviceNeverAcquireNativeWriter() = runTest {
        val f = StageFixture()
        f.prepare()
        f.base.setup.now += 2.days
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(0, f.acquisitions)
        assertEquals(1, f.base.records.value.size)
        val g = StageFixture()
        g.prepare()
        g.base.setup.onRead = {
            AppResult.Success(
                g.base.setup.device.copy(
                    id = g.device.deviceId,
                    state = CollaborationDeviceState.REVOKED,
                )
            )
        }
        assertIs<AppResult.Failed>(g.stage())
        assertEquals(0, g.acquisitions)
    }

    @Test
    fun lateOwnerReplacementClosesAcquiredWriterBeforeAnyEnqueue() = runTest {
        val f = StageFixture()
        f.prepare()
        f.afterAcquire = { f.base.setup.session = null }
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(0, f.enqueues)
        assertEquals(1, f.closes)
    }

    @Test
    fun companyDenialSurvivesFailedGateWriteAndFailedPostRequestSessionRead() = runTest {
        val f = StageFixture()
        f.prepare()
        f.control.remoteFailure = f.base.denied
        f.control.beforeRemote = { f.base.setup.sessionFailure = f.base.storage }
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.stage()).failure)
        assertEquals(f.base.denied, f.base.gate.value.denial)
        assertEquals(0, f.acquisitions)
    }

    @Test
    fun receivedRecipientDenialDoesNotRevokeAllLocalCompanyHistory() = runTest {
        val f = StageFixture()
        f.prepare()
        val denied =
            Failure(
                FailureKind.AccessDenied,
                "Unavailable",
                code = "collaboration_recipient_unavailable",
            )
        f.control.remoteFailure = denied
        assertEquals(denied, assertIs<AppResult.Failed>(f.stage()).failure)
        assertNull(f.base.gate.value.denial)
        assertEquals(0, f.acquisitions)
    }
}

internal class StageFixture {
    var origin = CollaborationMessageOrigin("@fixture:example.test", "a".repeat(64))
    var prepareFailure: Failure? = null
    var preparations = 0
    var afterPrepare: suspend () -> Unit = {}
    val base = MessageIntentFixture()
    val control = ConversationCommandFixture()
    val device =
        CollaborationMessageDevice(stageId(6), stageId(7), stageId(8), "!room:example.test")
    val owner: CollaborationOwner
        get() =
            CollaborationOwner(
                "local",
                base.server.audience,
                base.summary!!.conversation.firstAccountId,
                base.summary!!.conversation.companyId,
                device.nativeSessionId,
            )

    val record: CollaborationMessageRecord
        get() = base.records.value.first()

    val nativeIds = mutableSetOf<String>()
    val events = mutableListOf<String>()
    var acquisitions = 0
    var enqueues = 0
    var closes = 0
    var failStaging = false
    var failMapping = false
    var rejectMapping = false
    var snapshotFailure: Failure? = null
    var enqueueResult: AppResult<Unit> = AppResult.Success(Unit)
    var afterAcquire: suspend () -> Unit = {}
    var afterEnqueue: suspend () -> Unit = {}

    init {
        base.setup.session =
            base.setup.session!!.copy(
                binding = base.setup.session!!.binding.copy(serverId = device.nativeSessionId)
            )
        base.setup.workspace =
            base.setup.workspace!!.copy(
                admission = base.setup.workspace!!.admission.copy(familyId = device.nativeSessionId)
            )
        base.setup.onRead = { AppResult.Success(base.setup.device.copy(id = device.deviceId)) }
        control.conversation = base.summary!!.conversation
        control.room =
            CollaborationDirectRoom(
                control.conversation.id,
                1,
                CollaborationDirectRoomState.READY,
                device.roomId,
                control.conversation.firstAccountId,
                false,
                null,
            )
    }

    private val messages =
        object : CollaborationMessageRepository by base.messages {
            override suspend fun update(
                access: CollaborationLocalAccess,
                expected: CollaborationMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> {
                if (state is CollaborationMessageState.Staged && rejectMapping)
                    return AppResult.Success(false)
                if (
                    state is CollaborationMessageState.Staging && failStaging ||
                        state is CollaborationMessageState.Staged && failMapping
                )
                    return AppResult.Failed(base.storage)
                if (expected != record) return AppResult.Success(false)
                events +=
                    if (state is CollaborationMessageState.Staging) "save-staging"
                    else "save-staged"
                replace(expected.copy(revision = expected.revision + 1, state = state))
                return AppResult.Success(true)
            }
        }
    private val lists =
        object : CollaborationListRepository by base.lists {
            override suspend fun restrict(
                access: CollaborationLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> {
                base.gate.value = CollaborationListAdmission("received", failure)
                return AppResult.Failed(base.storage)
            }
        }
    private val runtimes =
        object : CollaborationRuntimeRepository {
            override suspend fun handoverIntent(
                owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent?
            > = error("Unexpected SDK handover in this fixture")

            override suspend fun stageHandover(
                owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
                deviceId: String,
                intent:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent
            > = error("Unexpected SDK handover in this fixture")

            override suspend fun updateRetention(
                retention:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeRetention
            ): AppResult<Unit> = closeOtherOwners(retention.foreground)

            override suspend fun acquireBackground(
                owner: CollaborationOwner,
                deviceId: String,
                lifetimeMillis: Long,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease?
            > = error("No implicit background runtime")

            override suspend fun readOwnReadReceipts(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationOwnReadReceipts
            > = error("No implicit read receipt lookup")

            override suspend fun sendReadReceipt(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                eventId: String,
                visibility:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationReadReceiptVisibility,
            ): AppResult<Unit> = error("No implicit read receipt delivery")

            override suspend fun synchronizeDevice(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Unit> = error("No implicit encryption activation")

            override suspend fun activatePusher(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Unit> = error("No implicit pusher activation")

            override suspend fun selfIdentity(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationSelfIdentity?
            > = error("No implicit identity acquisition")

            override suspend fun prepareHistoryReopen(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<Boolean> = error("No implicit history reopening")

            override suspend fun readProviderHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                position: String?,
                untilPosition: String?,
            ): AppResult<CollaborationProviderHistoryPage> =
                error("No implicit provider history page")

            override suspend fun findHistoryHead(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationHistoryHead> = error("No implicit history head lookup")

            override suspend fun readOriginalHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<CollaborationOriginalWindow> = error("No implicit history acquisition")

            override suspend fun acquireMessageQueue(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationMessageQueueLease> {
                acquisitions++
                val queue =
                    object : CollaborationMessageQueueLease {
                        override val owner = this@StageFixture.owner
                        override val device = this@StageFixture.device

                        override suspend fun snapshot(): AppResult<Set<String>> =
                            snapshotFailure?.let { AppResult.Failed(it) }
                                ?: AppResult.Success(nativeIds.toSet())

                        override suspend fun prepareText(
                            logicalId: String,
                            text: String,
                            replyToEventId: String?,
                            forwarded: Boolean,
                            editTarget: CollaborationEditTarget?,
                        ): AppResult<CollaborationMessageOrigin> {
                            assertEquals(record.intent.id, logicalId)
                            assertEquals(record.intent.text, text)
                            assertEquals(record.intent.replyToEventId, replyToEventId)
                            preparations++
                            afterPrepare()
                            return prepareFailure?.let { AppResult.Failed(it) }
                                ?: AppResult.Success(origin)
                        }

                        override suspend fun enqueue(
                            logicalId: String,
                            text: String,
                            origin: CollaborationMessageOrigin,
                            replyToEventId: String?,
                            forwarded: Boolean,
                            editTarget: CollaborationEditTarget?,
                        ): AppResult<Unit> {
                            assertEquals(
                                origin,
                                assertIs<CollaborationMessageState.Staging>(record.state).origin,
                            )
                            assertEquals(record.intent.id, logicalId)
                            assertEquals(record.intent.text, text)
                            assertEquals(record.intent.replyToEventId, replyToEventId)
                            enqueues++
                            events += "enqueue"
                            nativeIds += "original"
                            afterEnqueue()
                            return enqueueResult
                        }

                        override suspend fun resume(
                            mappedTransactions: Set<String>
                        ): AppResult<CollaborationQueueDelivery> =
                            error("Staging never enables delivery")

                        override suspend fun close(): AppResult<Unit> {
                            closes++
                            events += "close"
                            return AppResult.Success(Unit)
                        }
                    }
                afterAcquire()
                return AppResult.Success(queue)
            }

            override suspend fun isOpen(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Boolean> = error("No implicit restore")

            override suspend fun install(
                owner: CollaborationOwner,
                command: CollaborationSetupCommandRecord,
            ): AppResult<CollaborationRuntimeLease> = error("No enrollment")

            override suspend fun restore(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeLease?> = error("No implicit restore")

            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> = error("No implicit join")

            override suspend fun closeOtherOwners(
                retainedOwner: CollaborationOwner?
            ): AppResult<Unit> = error("No parent close")
        }
    private val staging =
        StageCollaborationText(
            messages,
            lists,
            control.remote,
            base.setup.remote,
            runtimes,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
        )

    suspend fun prepare(replyToEventId: String? = null) {
        assertIs<AppResult.Success<*>>(base.queue(replyToEventId = replyToEventId))
    }

    suspend fun stage(
        scope: CollaborationOperationScope = CollaborationOperationScope.Selected("selection"),
        deviceId: String = device.deviceId,
    ) = staging(scope, deviceId, record.intent.id)

    fun replace(value: CollaborationMessageRecord) {
        base.records.value =
            base.records.value.map { if (it.intent.id == value.intent.id) value else it }
    }
}

private fun stageId(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
