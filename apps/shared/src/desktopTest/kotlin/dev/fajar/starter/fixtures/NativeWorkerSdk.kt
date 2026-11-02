package dev.fajar.starter.fixtures

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import java.security.MessageDigest
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns simulated provider facts independently of application containers; not a native SDK proof.
 */
class NativeWorkerSdk(private val wire: NativeCollaborationUi) : CollaborationRuntimeRepository {
    private var owner: CollaborationOwner? = null
    private val originals = mutableMapOf<String, MutableList<CollaborationHistoryChange.Text>>()
    private val queued = mutableMapOf<String, MutableMap<String, CollaborationHistoryChange.Text>>()
    val enqueued = mutableListOf<String>()
    val attemptedRooms = mutableListOf<String>()
    var deliveries = 0
    var childCloses = 0
    var childOpens = 0
    var queueCloses = 0
    var restores = 0
    var restoreFailure: Failure? = null
    var loseDeliveryReply = false
    var withholdOriginals = false
    var historyCapacityOnce = false
    var historyReopens = 0
    var beforeResume: suspend () -> Unit = {}
    var beforeEnter: suspend () -> Unit = {}
    val sender = "@fictional:example.test"

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

    override suspend fun activatePusher(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<Unit> = error("No implicit pusher activation")

    override suspend fun selfIdentity(owner: CollaborationOwner, deviceId: String) =
        AppResult.Success(CollaborationSelfIdentity(sender))

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
    ): AppResult<CollaborationProviderHistoryPage> = error("No provider pagination")

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
        return AppResult.Success(
            CollaborationOriginalWindow(
                owner,
                deviceId,
                matrixRoomId,
                anchorEventId,
                if (withholdOriginals) emptyList() else originals[matrixRoomId].orEmpty().toList(),
                if (withholdOriginals) listOf(anchorEventId) else emptyList(),
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

                override suspend fun prepareText(logicalId: String, text: String) =
                    AppResult.Success(CollaborationMessageOrigin(sender, hash(text)))

                override suspend fun enqueue(
                    logicalId: String,
                    text: String,
                    origin: CollaborationMessageOrigin,
                ): AppResult<Unit> {
                    enqueued += logicalId
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
