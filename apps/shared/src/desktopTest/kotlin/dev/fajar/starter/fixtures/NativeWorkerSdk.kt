package dev.fajar.starter.fixtures

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import java.security.MessageDigest
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns simulated provider facts independently of application containers; not a native SDK proof.
 */
class NativeWorkerSdk(private val wire: NativeCollaborationUi) : CollaborationRuntimeRepository {
    override suspend fun handoverIntent(
        owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
        deviceId: String,
    ): AppResult<
        dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent?
    > = error("Unexpected SDK handover in this fixture")

    override suspend fun stageHandover(
        owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
        deviceId: String,
        intent: dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent,
    ): AppResult<
        dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent
    > = error("Unexpected SDK handover in this fixture")

    private var owner: CollaborationOwner? = null
    private val originals = mutableMapOf<String, MutableList<CollaborationHistoryChange.Text>>()
    private val queued = mutableMapOf<String, MutableMap<String, CollaborationHistoryChange.Text>>()
    val enqueued = mutableListOf<String>()
    val enqueuedReplies = mutableMapOf<String, String?>()
    val attemptedRooms = mutableListOf<String>()
    var deliveries = 0
    var childCloses = 0
    var childOpens = 0
    var queueCloses = 0
    var restores = 0
    var backgroundEnabled = false
    var backgroundAcquisitions = 0
    var backgroundPublications = 0
    var backgroundCloses = 0
    var beforeBackgroundReturn: suspend () -> Unit = {}
    val backgroundInterruptions = MutableStateFlow<Failure?>(null)
    val backgroundCredentialFailures = MutableStateFlow<Failure?>(null)
    var restoreFailure: Failure? = null
    var loseDeliveryReply = false
    var withholdOriginals = false
    var historyCapacityOnce = false
    var historyReopens = 0
    var historyReads = 0
    var historyWindowLimit = 128
    val withheldHistory = mutableSetOf<String>()
    val providerBounds = mutableListOf<Pair<String?, String?>>()
    var beforeProvider: suspend () -> Unit = {}
    var beforeHistory: suspend () -> Unit = {}
    var beforeResume: suspend () -> Unit = {}
    var beforeEnter: suspend () -> Unit = {}
    val readReceipts = mutableListOf<Triple<String, String, CollaborationReadReceiptVisibility>>()
    var activeReadReceipts = 0
    var beforeReadReceipt: suspend () -> Unit = {}
    var readReceiptFailure: Failure? = null
    val sender = "@fictional:example.test"
    var beforeIdentity: suspend () -> Unit = {}
    var identityResult: AppResult<CollaborationSelfIdentity?> =
        AppResult.Success(CollaborationSelfIdentity(sender))
    var identityReads = 0

    fun incoming(conversationId: String, text: String): CollaborationHistoryChange.Text {
        check(conversationId in wire.ids)
        val id = java.util.UUID.randomUUID().toString()
        val event =
            CollaborationHistoryChange.Text(
                "\$incoming-$id",
                "@peer:example.test",
                id,
                Clock.System.now().toEpochMilliseconds(),
                text,
                hash(text),
            )
        originals.getOrPut(wire.roomId(conversationId)) { mutableListOf() }.add(event)
        return event
    }

    override suspend fun isOpen(owner: CollaborationOwner, deviceId: String) =
        AppResult.Success(this.owner == owner && deviceId == wire.deviceId)

    override suspend fun install(
        owner: CollaborationOwner,
        command: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationRuntimeLease> = error("No automatic bootstrap")

    override suspend fun restore(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeLease?> {
        restores++
        restoreFailure?.let {
            return AppResult.Failed(it)
        }
        check(deviceId == wire.deviceId)
        this.owner = owner
        return AppResult.Success(
            object : CollaborationRuntimeLease {
                override val owner = owner
                override val deviceId = deviceId
                override val installedOperationId = wire.bootstrapId
                override val credentialFailures = MutableStateFlow<Failure?>(null)

                override fun publish(): AppResult<Unit> = AppResult.Success(Unit)

                override suspend fun retryCredentialWrite(): AppResult<Unit> =
                    error("No implicit credential retry")

                override suspend fun close(): AppResult<Unit> {
                    this@NativeWorkerSdk.owner = null
                    return AppResult.Success(Unit)
                }
            }
        )
    }

    override suspend fun closeOtherOwners(retainedOwner: CollaborationOwner?): AppResult<Unit> {
        if (owner != retainedOwner) owner = null
        return AppResult.Success(Unit)
    }

    override suspend fun updateRetention(
        retention: dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeRetention
    ): AppResult<Unit> = closeOtherOwners(retention.foreground)

    override suspend fun acquireBackground(
        owner: CollaborationOwner,
        deviceId: String,
        lifetimeMillis: Long,
    ): AppResult<CollaborationBackgroundRuntimeLease?> {
        check(backgroundEnabled) { "No implicit background runtime" }
        check(deviceId == wire.deviceId && lifetimeMillis in 1..60_000)
        backgroundAcquisitions++
        val foreground = this.owner
        val lease =
            object : CollaborationBackgroundRuntimeLease {
                override val owner = owner
                override val deviceId = deviceId
                override val interruptions = backgroundInterruptions
                override val credentialFailures = backgroundCredentialFailures
                private var closed = false
                private var published = false

                override fun publish(): AppResult<Unit> {
                    check(!closed)
                    if (!published) {
                        published = true
                        backgroundPublications++
                        this@NativeWorkerSdk.owner = owner
                    }
                    return AppResult.Success(Unit)
                }

                override suspend fun close(): AppResult<Unit> {
                    if (!closed) {
                        closed = true
                        backgroundCloses++
                        if (published && this@NativeWorkerSdk.owner == owner)
                            this@NativeWorkerSdk.owner = foreground
                    }
                    return AppResult.Success(Unit)
                }
            }
        var transferred = false
        try {
            beforeBackgroundReturn()
            transferred = true
            return AppResult.Success(lease)
        } finally {
            if (!transferred) withContext(NonCancellable) { lease.close() }
        }
    }

    override suspend fun readOwnReadReceipts(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<dev.fajar.starter.collaboration.domain.entities.CollaborationOwnReadReceipts> =
        error("No implicit read receipt lookup")

    override suspend fun sendReadReceipt(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        eventId: String,
        visibility:
            dev.fajar.starter.collaboration.domain.entities.CollaborationReadReceiptVisibility,
    ): AppResult<Unit> {
        check(this.owner == owner && deviceId == wire.deviceId)
        check(originals[matrixRoomId].orEmpty().any { it.eventId == eventId })
        readReceipts += Triple(matrixRoomId, eventId, visibility)
        activeReadReceipts++
        try {
            beforeReadReceipt()
            return readReceiptFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        } finally {
            activeReadReceipts--
        }
    }

    override suspend fun activatePusher(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<Unit> = error("No implicit pusher activation")

    override suspend fun selfIdentity(owner: CollaborationOwner, deviceId: String) = run {
        check(this.owner == owner && deviceId == wire.deviceId)
        identityReads++
        beforeIdentity()
        identityResult
    }

    override suspend fun prepareHistoryReopen(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        anchorEventId: String,
    ): AppResult<Boolean> {
        historyReopens++
        this.owner = null
        return AppResult.Success(true)
    }

    override suspend fun readProviderHistory(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        position: String?,
        untilPosition: String?,
    ): AppResult<CollaborationProviderHistoryPage> {
        check(this.owner == owner && deviceId == wire.deviceId)
        val events = originals[matrixRoomId].orEmpty().toList()
        val from = position?.removePrefix("history-")?.toInt() ?: events.size
        val until = untilPosition?.removePrefix("history-")?.toInt() ?: 0
        check(until in 0..from && from <= events.size)
        val end = maxOf(until, from - 20)
        val page =
            CollaborationProviderHistoryPage(
                owner,
                deviceId,
                matrixRoomId,
                position,
                position ?: "history-$from",
                if (end > until) "history-$end" else null,
                events.subList(end, from).asReversed().map {
                    CollaborationProviderHistoryEvent(
                        it.eventId,
                        it.sender,
                        it.timestamp,
                        "m.room.encrypted",
                    )
                },
                untilPosition,
            )
        providerBounds += position to untilPosition
        beforeProvider()
        return AppResult.Success(page)
    }

    override suspend fun findHistoryHead(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ) =
        AppResult.Success(
            CollaborationHistoryHead(
                owner,
                deviceId,
                matrixRoomId,
                if (withholdOriginals) null else originals[matrixRoomId]?.lastOrNull()?.eventId,
            )
        )

    override suspend fun readOriginalHistory(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        anchorEventId: String,
    ): AppResult<CollaborationOriginalWindow> {
        historyReads++
        beforeHistory()
        if (historyCapacityOnce) {
            historyCapacityOnce = false
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Fixture focus capacity",
                    code = "client.collaboration_history_focus_capacity",
                )
            )
        }
        val events = originals[matrixRoomId].orEmpty()
        val index = events.indexOfFirst { it.eventId == anchorEventId }
        check(index >= 0 && historyWindowLimit in 1..128)
        val start = maxOf(0, index - historyWindowLimit / 2)
        val window = events.subList(start, minOf(events.size, start + historyWindowLimit))
        return AppResult.Success(
            CollaborationOriginalWindow(
                owner,
                deviceId,
                matrixRoomId,
                anchorEventId,
                if (withholdOriginals) emptyList()
                else window.filter { it.eventId !in withheldHistory },
                if (withholdOriginals) listOf(anchorEventId)
                else window.filter { it.eventId in withheldHistory }.map { it.eventId },
            )
        )
    }

    override suspend fun enterTimeline(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationTimelineLease> {
        check(this.owner == owner)
        attemptedRooms += matrixRoomId
        childOpens++
        beforeEnter()
        return AppResult.Success(
            object : CollaborationTimelineLease {
                override val owner = owner
                override val deviceId = deviceId
                override val matrixRoomId = matrixRoomId
                override val device = this@NativeWorkerSdk.device(owner, matrixRoomId)
                override val updates =
                    MutableStateFlow<AppResult<List<CollaborationTimelineItem>>>(
                        AppResult.Success(emptyList())
                    )

                override suspend fun close(): AppResult<Unit> {
                    childCloses++
                    return AppResult.Success(Unit)
                }
            }
        )
    }

    override suspend fun acquireMessageQueue(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationMessageQueueLease> {
        check(this.owner == owner)
        val pending = queued.getOrPut(matrixRoomId) { linkedMapOf() }
        return AppResult.Success(
            object : CollaborationMessageQueueLease {
                override val owner = owner
                override val device = this@NativeWorkerSdk.device(owner, matrixRoomId)

                override suspend fun snapshot() = AppResult.Success(pending.keys.toSet())

                override suspend fun prepareText(
                    logicalId: String,
                    text: String,
                    replyToEventId: String?,
                ) =
                    AppResult.Success(
                        CollaborationMessageOrigin(
                            sender,
                            hash(
                                if (replyToEventId == null) text
                                else text + "\u0000" + replyToEventId
                            ),
                        )
                    )

                override suspend fun enqueue(
                    logicalId: String,
                    text: String,
                    origin: CollaborationMessageOrigin,
                    replyToEventId: String?,
                ): AppResult<Unit> {
                    enqueued += logicalId
                    enqueuedReplies[logicalId] = replyToEventId
                    val transaction = "tx-$logicalId"
                    check(transaction !in pending)
                    pending[transaction] =
                        CollaborationHistoryChange.Text(
                            "\$event-$logicalId",
                            sender,
                            logicalId,
                            Clock.System.now().toEpochMilliseconds(),
                            text,
                            origin.contentHash,
                            replyToEventId,
                        )
                    return AppResult.Success(Unit)
                }

                override suspend fun resume(
                    mappedTransactions: Set<String>
                ): AppResult<CollaborationQueueDelivery> {
                    deliveries++
                    beforeResume()
                    val sent =
                        mappedTransactions.mapNotNull { tx -> pending.remove(tx)?.let { tx to it } }
                    originals
                        .getOrPut(matrixRoomId) { mutableListOf() }
                        .addAll(sent.map { it.second })
                    if (loseDeliveryReply) {
                        loseDeliveryReply = false
                        return AppResult.Failed(
                            Failure(FailureKind.Network, "Fixture lost delivery response")
                        )
                    }
                    return AppResult.Success(
                        CollaborationQueueDelivery(
                            sent.map { CollaborationQueueReceipt(it.first, it.second.eventId) },
                            pending.keys.toSet(),
                            CollaborationQueueDeliveryReason.DRAINED,
                        )
                    )
                }

                override suspend fun close(): AppResult<Unit> {
                    queueCloses++
                    return AppResult.Success(Unit)
                }
            }
        )
    }

    private fun device(owner: CollaborationOwner, room: String) =
        CollaborationMessageDevice(owner.nativeSessionId, wire.deviceId, wire.bootstrapId, room)

    private fun hash(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
