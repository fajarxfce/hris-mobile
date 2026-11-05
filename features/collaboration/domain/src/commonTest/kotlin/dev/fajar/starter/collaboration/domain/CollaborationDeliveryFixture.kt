package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class CollaborationDeliveryFixture {
    val base = MessageIntentFixture()
    val completions = CollaborationCompletionFixture(base)
    val control = ConversationCommandFixture()
    val device =
        CollaborationMessageDevice(
            deliveryId(6),
            deliveryId(7),
            deliveryId(8),
            "!room:example.test",
        )
    val records
        get() = base.records.value

    val conversation
        get() = base.summary!!.conversation.id

    val owner
        get() =
            CollaborationOwner(
                "local",
                base.server.audience,
                base.summary!!.conversation.firstAccountId,
                base.summary!!.conversation.companyId,
                device.nativeSessionId,
            )

    var journal = emptyList<CollaborationMessageReceipt>()
    var window =
        CollaborationQueueDelivery(
            listOf(CollaborationQueueReceipt("original", "\$first")),
            emptySet(),
            CollaborationQueueDeliveryReason.DRAINED,
        )
    var resumeFailure: Failure? = null
    var appendFailure: Failure? = null
    var journalFailure: Failure? = null
    var rejectAppendAt = 0
    var rejectProjection = false
    var acquisitions = 0
    var windows = 0
    var appends = 0
    var projections = 0
    var closes = 0
    val events = mutableListOf<String>()
    var knownTransactions = emptySet<String>()
    var retainedTransactions = setOf("original")
    var snapshotFailure: Failure? = null
    var afterAcquire: suspend () -> Unit = {}
    var afterResume: suspend () -> Unit = {}
    var beforeAppend: suspend () -> Unit = {}
    var afterAppend: suspend () -> Unit = {}
    var afterProject: suspend () -> Unit = {}

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
                conversation,
                1,
                CollaborationDirectRoomState.READY,
                device.roomId,
                control.conversation.firstAccountId,
                false,
                null,
            )
    }

    val messages =
        object : CollaborationMessageRepository by base.messages {
            override suspend fun update(
                access: CollaborationLocalAccess,
                expected: CollaborationMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> {
                assertIs<CollaborationMessageState.Sent>(state)
                assertTrue(
                    journal.any { it.intent == expected.intent && state.eventId in it.eventIds }
                )
                projections++
                if (rejectProjection || expected !in records) return AppResult.Success(false)
                base.records.value =
                    records.map {
                        if (it == expected) it.copy(revision = it.revision + 1, state = state)
                        else it
                    }
                events += "project"
                afterProject()
                return AppResult.Success(true)
            }
        }

    val receipts =
        object : CollaborationReceiptRepository {
            override suspend fun read(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<List<CollaborationMessageReceipt>> =
                journalFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(journal.toList())

            override suspend fun append(
                access: CollaborationLocalAccess,
                conversationId: String,
                receipts: List<CollaborationMessageReceipt>,
            ): AppResult<Boolean> {
                appends++
                assertTrue(receipts.size in 1..32)
                beforeAppend()
                // The real repository's SQLite/IndexedDB tests verify this fence at atomic commit.
                val current = base.setup.session
                if (
                    current?.id != access.sessionId ||
                        current.user.id != access.accountId ||
                        appends == rejectAppendAt
                )
                    return AppResult.Success(false)
                val merged = journal.associateBy { it.intent.id }.toMutableMap()
                for (receipt in receipts) {
                    val old = merged[receipt.intent.id]
                    merged[receipt.intent.id] =
                        if (old == null) receipt
                        else {
                            assertEquals(old.intent, receipt.intent)
                            assertEquals(old.device, receipt.device)
                            assertEquals(old.transactionId, receipt.transactionId)
                            old.copy(eventIds = (old.eventIds + receipt.eventIds).distinct())
                        }
                }
                journal = merged.values.toList()
                assertTrue(validCollaborationReceiptLedger(journal))
                events += "journal"
                afterAppend()
                return appendFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }

    val lists =
        object : CollaborationListRepository by base.lists {
            override suspend fun restrict(
                access: CollaborationLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> {
                base.gate.value = CollaborationListAdmission("received", failure)
                return AppResult.Failed(base.storage)
            }
        }

    val runtimes =
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
                        override val owner = this@CollaborationDeliveryFixture.owner
                        override val device = this@CollaborationDeliveryFixture.device

                        override suspend fun snapshot(): AppResult<Set<String>> =
                            snapshotFailure?.let { AppResult.Failed(it) }
                                ?: AppResult.Success(retainedTransactions)

                        override suspend fun prepareText(
                            logicalId: String,
                            text: String,
                            replyToEventId: String?,
                            forwarded: Boolean,
                        ): AppResult<CollaborationMessageOrigin> =
                            error("Delivery never prepares content")

                        override suspend fun enqueue(
                            logicalId: String,
                            text: String,
                            origin: CollaborationMessageOrigin,
                            replyToEventId: String?,
                            forwarded: Boolean,
                        ): AppResult<Unit> = error("Delivery never replaces native work")

                        override suspend fun resume(
                            mappedTransactions: Set<String>
                        ): AppResult<CollaborationQueueDelivery> {
                            windows++
                            knownTransactions = mappedTransactions.toSet()
                            events += "resume"
                            val captured =
                                resumeFailure?.let { AppResult.Failed(it) }
                                    ?: AppResult.Success(window)
                            afterResume()
                            return captured
                        }

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
            ): AppResult<CollaborationRuntimeLease?> = error("No restore")

            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> = error("No join")

            override suspend fun closeOtherOwners(
                retainedOwner: CollaborationOwner?
            ): AppResult<Unit> = error("No parent close")
        }
    private val delivery =
        DeliverCollaborationMessages(
            messages,
            receipts,
            lists,
            control.remote,
            base.setup.remote,
            runtimes,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
            completions,
        )
    private val reconcile =
        ReconcileCollaborationMessages(
            messages,
            receipts,
            lists,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
            completions,
        )

    suspend fun prepare(count: Int = 1) {
        val queued = assertIs<AppResult.Success<CollaborationMessageRecord>>(base.queue()).value
        base.records.value =
            (1..count).map { index ->
                queued.copy(
                    intent = queued.intent.copy(id = deliveryId(100 + index)),
                    revision = 2,
                    state =
                        CollaborationMessageState.Staged(
                            device,
                            if (index == 1) "original" else "tx-$index",
                        ),
                )
            }
    }

    suspend fun deliver(
        scope: CollaborationOperationScope = CollaborationOperationScope.Selected("selection"),
        deviceId: String = device.deviceId,
    ) = delivery(scope, deviceId, conversation)

    suspend fun reconcile(
        scope: CollaborationLocalScope = CollaborationLocalScope.Selected("selection")
    ) = reconcile(scope, conversation)

    fun receipt(record: CollaborationMessageRecord = records.first(), event: String = "\$first") =
        CollaborationMessageReceipt(
            record.intent,
            device,
            (record.state as CollaborationMessageState.Staged).transactionId,
            listOf(event),
        )
}

internal fun deliveryId(value: Int) =
    "c3000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
