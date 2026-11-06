package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.SettleCollaborationCompletedQueue
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class SettleCollaborationCompletedQueueTest {
    @Test
    fun archivedOriginalSettlesWithoutSendingAndPreservesNewIntentAndCompletion() = runTest {
        val f = CompletedQueueFixture().apply { prepare() }
        val intent = f.stage.record
        val proof = f.completed.records.toMap()
        assertEquals(AppResult.Success(1), f.run())
        assertEquals(emptySet(), f.stage.nativeIds)
        assertEquals(listOf(f.original.transactionId), f.settled)
        assertEquals(intent, f.stage.record)
        assertEquals(proof, f.completed.records)
        assertEquals(0, f.stage.enqueues)
        assertEquals(1, f.stage.closes)
        assertEquals(AppResult.Success(0), f.run())
        assertEquals(1, f.settled.size)
    }

    @Test
    fun retainedBootstrapAliasStillRequiresSameNativeFamilyDeviceRoomAndCompleteContent() =
        runTest {
            val f = CompletedQueueFixture().apply { prepare() }
            val record = f.completion.record
            val sent = record.state as CollaborationMessageState.Sent
            f.completed.records[f.original.logicalId!!] =
                f.completion.copy(
                    record =
                        record.copy(
                            state =
                                sent.copy(
                                    device =
                                        sent.device.copy(bootstrapOperationId = completedId(900))
                                )
                        )
                )
            assertEquals(AppResult.Success(1), f.run())
        }

    @Test
    fun staleTimelineOwnerOrDeviceCannotAcquireAnotherWriter() = runTest {
        for (device in listOf(false, true)) {
            val f = CompletedQueueFixture().apply { prepare() }
            if (device) f.timelineDevice = f.stage.device.copy(nativeSessionId = completedId(900))
            else f.timelineOwner = f.stage.owner.copy(accountId = completedId(900))
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.stage.acquisitions)
            assertTrue(f.settled.isEmpty())
        }
    }

    @Test
    fun mismatchedArchiveNeverRemovesNativeWork() = runTest {
        for (scenario in 0..10) {
            val f = CompletedQueueFixture().apply { prepare() }
            val record = f.completion.record
            val sent = record.state as CollaborationMessageState.Sent
            val changed =
                when (scenario) {
                    0 ->
                        record.copy(
                            intent =
                                record.intent.copy(
                                    owner = record.intent.owner.copy(accountId = completedId(900))
                                )
                        )
                    1 ->
                        record.copy(
                            intent =
                                record.intent.copy(
                                    owner = record.intent.owner.copy(companyId = completedId(900))
                                )
                        )
                    2 -> record.copy(intent = record.intent.copy(conversationId = completedId(900)))
                    3 -> record.copy(intent = record.intent.copy(id = completedId(900)))
                    4 ->
                        record.copy(
                            state =
                                sent.copy(
                                    device = sent.device.copy(nativeSessionId = completedId(900))
                                )
                        )
                    5 ->
                        record.copy(
                            state =
                                sent.copy(device = sent.device.copy(deviceId = completedId(900)))
                        )
                    6 ->
                        record.copy(
                            state =
                                sent.copy(device = sent.device.copy(roomId = "!other:example.test"))
                        )
                    7 -> record.copy(state = sent.copy(transactionId = "other-transaction"))
                    8 ->
                        record.copy(
                            state =
                                sent.copy(
                                    origin = f.stage.origin.copy(sender = "@other:example.test")
                                )
                        )
                    9 ->
                        record.copy(
                            state =
                                sent.copy(
                                    origin = f.stage.origin.copy(contentHash = "b".repeat(64))
                                )
                        )
                    else -> record.copy(revision = 0)
                }
            f.completed.records[f.original.logicalId!!] = f.completion.copy(record = changed)
            assertIs<AppResult.Failed>(f.run(), "scenario $scenario")
            assertEquals(setOf(f.original.transactionId), f.stage.nativeIds)
            assertTrue(f.settled.isEmpty())
            assertEquals(1, f.stage.closes)
        }
    }

    @Test
    fun archiveWithoutConfirmedPrimaryEventCannotSettle() = runTest {
        val f = CompletedQueueFixture().apply { prepare() }
        f.completed.records[f.original.logicalId!!] =
            f.completion.copy(eventIds = listOf("$" + "unrelated"))
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.settled.isEmpty())
    }

    @Test
    fun unknownUnarchivedEntryRemainsForExistingStagingGuard() = runTest {
        val f = CompletedQueueFixture().apply { prepare() }
        f.completed.records.clear()
        assertEquals(AppResult.Success(0), f.run())
        assertEquals(setOf(f.original.transactionId), f.stage.nativeIds)
        assertIs<AppResult.Failed>(f.stage.stage())
        assertEquals(0, f.stage.enqueues)
    }

    @Test
    fun activeMappedAndUniquelyRecoverableStagingDoNotNeedOriginalReadCapability() = runTest {
        for (staging in listOf(false, true)) {
            val f = CompletedQueueFixture().apply { prepare() }
            f.stage.nativeIds.clear()
            f.stage.nativeIds += "original"
            val state =
                if (staging)
                    CollaborationMessageState.Staging(f.stage.device, emptySet(), f.stage.origin)
                else CollaborationMessageState.Staged(f.stage.device, "original", f.stage.origin)
            f.stage.replace(f.stage.record.copy(revision = 1, state = state))
            f.beforeOriginals = {
                error("Mapped/uncertain original must retain its existing recovery path")
            }
            assertEquals(AppResult.Success(0), f.run())
            assertIs<AppResult.Success<*>>(f.stage.stage())
            assertTrue(f.settled.isEmpty())
            assertEquals(0, f.stage.enqueues)
        }
    }

    @Test
    fun uncertainBeforeSnapshotCannotLoseItsReferencedOriginal() = runTest {
        val f = CompletedQueueFixture().apply { prepare() }
        f.stage.replace(
            f.stage.record.copy(
                revision = 1,
                state =
                    CollaborationMessageState.Staging(
                        f.stage.device,
                        setOf(f.original.transactionId),
                        f.stage.origin,
                    ),
            )
        )
        assertIs<AppResult.Failed>(f.run())
        assertEquals(setOf(f.original.transactionId), f.stage.nativeIds)
        assertTrue(f.settled.isEmpty())
    }

    @Test
    fun incompleteDuplicateOrUnboundedSnapshotNeverSettles() = runTest {
        for (scenario in 0..4) {
            val f = CompletedQueueFixture().apply { prepare() }
            f.raw =
                when (scenario) {
                    0 -> emptyList()
                    1 -> listOf(f.original, f.original)
                    2 -> listOf(f.original.copy(transactionId = "other"))
                    3 -> listOf(f.original.copy(eventType = "m.room.redaction"))
                    else -> listOf(f.original.copy(serializedContent = "é".repeat(48_001)))
                }
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.settled.isEmpty())
        }
    }

    @Test
    fun failedProofReadOrRevokedOwnerRetainsAllOriginals() = runTest {
        for (revoked in listOf(false, true)) {
            val f = CompletedQueueFixture().apply { prepare() }
            if (revoked) f.completed.afterRead = { f.stage.base.setup.session = null }
            else f.completed.readFailure = f.stage.base.storage
            assertIs<AppResult.Failed>(f.run())
            assertEquals(setOf(f.original.transactionId), f.stage.nativeIds)
            assertTrue(f.settled.isEmpty())
            assertEquals(1, f.stage.closes)
        }
    }

    @Test
    fun changedActiveRecordBeforeSettlementRetainsOriginal() = runTest {
        val f = CompletedQueueFixture().apply { prepare() }
        f.completed.afterRead = {
            f.stage.replace(f.stage.record.copy(revision = f.stage.record.revision + 1))
        }
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.settled.isEmpty())
        assertEquals(setOf(f.original.transactionId), f.stage.nativeIds)
    }

    @Test
    fun cancellationDuringOriginalReadClosesWriterAndRetainsQueue() = runTest {
        val f = CompletedQueueFixture().apply { prepare() }
        val entered = CompletableDeferred<Unit>()
        f.beforeOriginals = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.run() }
        entered.await()
        task.cancelAndJoin()
        assertTrue(f.settled.isEmpty())
        assertEquals(setOf(f.original.transactionId), f.stage.nativeIds)
        assertEquals(1, f.stage.closes)
    }

    @Test
    fun cancellationAfterNativeSettlementRecoversWithoutAnotherRemovalOrSend() = runTest {
        val f = CompletedQueueFixture().apply { prepare() }
        val entered = CompletableDeferred<Unit>()
        f.afterSettlement = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.run() }
        entered.await()
        task.cancelAndJoin()
        assertTrue(f.stage.nativeIds.isEmpty())
        f.afterSettlement = {}
        assertEquals(AppResult.Success(0), f.run())
        assertEquals(1, f.settled.size)
        assertEquals(0, f.stage.enqueues)
        assertEquals(2, f.stage.closes)
    }

    @Test
    fun failedNativeSettlementRetainsOriginalAndRetriesSameProof() = runTest {
        val f = CompletedQueueFixture().apply { prepare() }
        f.settlementFailure = f.stage.base.storage
        assertIs<AppResult.Failed>(f.run())
        assertEquals(setOf(f.original.transactionId), f.stage.nativeIds)
        f.settlementFailure = null
        assertEquals(AppResult.Success(1), f.run())
        assertEquals(listOf(f.original.transactionId), f.settled)
    }

    @Test
    fun unexpectedQueueChangeAfterSettlementDoesNotAuthorizeAnotherSend() = runTest {
        val f = CompletedQueueFixture().apply { prepare() }
        f.afterSettlement = { f.stage.nativeIds += "foreign" }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(setOf("foreign"), f.stage.nativeIds)
        assertEquals(0, f.stage.enqueues)
    }
}

private class CompletedQueueFixture {
    val stage = StageFixture()
    val completed = CollaborationCompletionFixture(stage.base)
    lateinit var completion: CollaborationMessageCompletion
    lateinit var original: CollaborationQueuedOriginal
    var raw: List<CollaborationQueuedOriginal>? = null
    val settled = mutableListOf<String>()
    var beforeOriginals: suspend () -> Unit = {}
    var afterSettlement: suspend () -> Unit = {}
    var settlementFailure: Failure? = null

    private val runtimes =
        object : CollaborationRuntimeRepository by stage.runtimes {
            override suspend fun acquireMessageQueue(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationMessageQueueLease> {
                val acquired = stage.runtimes.acquireMessageQueue(owner, deviceId, matrixRoomId)
                if (acquired is AppResult.Failed) return acquired
                val queue = (acquired as AppResult.Success).value
                return AppResult.Success(
                    object : CollaborationMessageQueueLease by queue {
                        override suspend fun originals():
                            AppResult<List<CollaborationQueuedOriginal>> {
                            beforeOriginals()
                            return AppResult.Success(raw ?: listOf(original))
                        }

                        override suspend fun settleConfirmed(
                            original: CollaborationQueuedOriginal,
                            eventId: String,
                        ): AppResult<CollaborationQueueSettlement> {
                            settlementFailure?.let {
                                return AppResult.Failed(it)
                            }
                            assertEquals(this@CompletedQueueFixture.original, original)
                            assertEquals(
                                (completion.record.state as CollaborationMessageState.Sent).eventId,
                                eventId,
                            )
                            assertTrue(stage.nativeIds.remove(original.transactionId))
                            settled += original.transactionId
                            afterSettlement()
                            return AppResult.Success(CollaborationQueueSettlement.Settled)
                        }
                    }
                )
            }
        }
    private val reconcile =
        SettleCollaborationCompletedQueue(
            stage.messages,
            completed,
            stage.lists,
            runtimes,
            stage.base.setup.sessions,
            stage.base.setup.workspaces,
            stage.base.server,
            stage.base.build,
            stage.base.clock,
        )

    suspend fun prepare() {
        stage.prepare()
        val record =
            stage.record.copy(
                intent = stage.record.intent.copy(id = completedId(999), text = "Older original"),
                revision = 3,
                state =
                    CollaborationMessageState.Sent(
                        stage.device,
                        "old-native",
                        "$" + "accepted",
                        stage.origin,
                    ),
            )
        completion = CollaborationMessageCompletion(record, listOf("$" + "accepted"))
        original =
            CollaborationQueuedOriginal(
                "old-native",
                record.intent.id,
                stage.origin,
                "m.room.message",
                "{}",
            )
        completed.records[record.intent.id] = completion
        stage.nativeIds += original.transactionId
    }

    var timelineOwner: CollaborationOwner? = null
    var timelineDevice: CollaborationMessageDevice? = null

    suspend fun run() =
        reconcile(
            CollaborationOperationScope.Selected("selection"),
            stage.record.intent.conversationId,
            object : CollaborationTimelineLease {
                override val owner = timelineOwner ?: stage.owner
                override val device = timelineDevice ?: stage.device
                override val deviceId = device.deviceId
                override val matrixRoomId = device.roomId
                override val updates = emptyFlow<AppResult<List<CollaborationTimelineItem>>>()

                override suspend fun close(): AppResult<Unit> =
                    error("Settlement never owns the caller's timeline")
            },
        )
}

private fun completedId(value: Int) =
    "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
