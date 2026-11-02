package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.DeliverCollaborationReadReceipts
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

internal class CollaborationReadReceiptDeliveryFixture {
    val localState = CollaborationReadStateFixture()
    val local = localState.local
    val delivery = CollaborationDeliveryFixture()
    val owner = delivery.owner
    val deviceId = delivery.device.deviceId
    val conversationId = localState.conversationId
    val roomId = localState.roomId
    val events = mutableListOf<String>()
    val sends = mutableListOf<Pair<String, CollaborationReadReceiptVisibility>>()
    val acknowledgements = mutableListOf<CollaborationReadReceiptIntent>()
    var sendResult: AppResult<Unit> = AppResult.Success(Unit)
    var ackResult: AppResult<Boolean> = AppResult.Success(true)
    var commitAckOnFailure = false
    var afterSend: suspend () -> Unit = {}
    var afterAck: suspend () -> Unit = {}
    var activeSends = 0
    var restrictions = 0

    init {
        local.setup.session =
            local.setup.session!!.copy(
                binding = local.setup.session!!.binding.copy(serverId = owner.nativeSessionId)
            )
        local.setup.workspace =
            local.setup.workspace!!.copy(
                admission = local.setup.workspace!!.admission.copy(familyId = owner.nativeSessionId)
            )
        local.setup.onRead = {
            events += "device"
            AppResult.Success(local.setup.device.copy(id = deviceId))
        }
        delivery.control.conversation = local.summary!!.conversation
        delivery.control.room = delivery.control.room.copy(matrixRoomId = roomId)
        delivery.control.beforeRemote = { events += it }
        append(localState.intent)
    }

    fun append(intent: CollaborationReadReceiptIntent) {
        localState.base.facts[intent.eventId] =
            CollaborationHistoryEventFact(intent.eventId, "@fixture:test", 0, false)
        localState.state =
            localState.state.copy(
                version =
                    CollaborationReadStateRevision(
                        CollaborationReadStateFixture.id(2),
                        (localState.state.version?.revision ?: -1) + 1,
                    ),
                readEventIds = localState.state.readEventIds + intent.eventId,
                pending = localState.state.pending + intent,
            )
    }

    val states =
        object : CollaborationReadStateRepository by localState.repo {
            override suspend fun acknowledge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                intent: CollaborationReadReceiptIntent,
            ): AppResult<Boolean> {
                assertEquals(owner.localListAccess(), access)
                assertEquals(
                    this@CollaborationReadReceiptDeliveryFixture.conversationId,
                    conversationId,
                )
                assertEquals(this@CollaborationReadReceiptDeliveryFixture.roomId, roomId)
                events += "ack"
                acknowledgements += intent
                if (ackResult == AppResult.Success(true) || commitAckOnFailure) {
                    assertTrue(intent in localState.state.pending)
                    localState.state =
                        localState.state.copy(
                            version =
                                localState.state.version!!.copy(
                                    revision = localState.state.version!!.revision + 1
                                ),
                            pending = localState.state.pending.filterNot { it == intent },
                            acknowledged = intent,
                        )
                }
                afterAck()
                return ackResult
            }
        }
    private val runtime =
        object : CollaborationRuntimeRepository by delivery.runtimes {
            override suspend fun sendReadReceipt(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                eventId: String,
                visibility: CollaborationReadReceiptVisibility,
            ): AppResult<Unit> {
                assertEquals(this@CollaborationReadReceiptDeliveryFixture.owner, owner)
                assertEquals(this@CollaborationReadReceiptDeliveryFixture.deviceId, deviceId)
                assertEquals(roomId, matrixRoomId)
                sends += eventId to visibility
                events += "send"
                activeSends++
                try {
                    afterSend()
                    return sendResult
                } finally {
                    activeSends--
                }
            }
        }
    private val lists =
        object : CollaborationListRepository by local.lists {
            override suspend fun restrict(
                access: CollaborationLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> {
                restrictions++
                local.gate.value = CollaborationListAdmission("retained-denial", failure)
                return AppResult.Failed(local.storage)
            }
        }
    val useCase =
        DeliverCollaborationReadReceipts(
            states,
            localState.base.history,
            lists,
            delivery.control.remote,
            local.setup.remote,
            runtime,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    suspend fun run(
        limit: Int = 4,
        scope: CollaborationOperationScope = CollaborationOperationScope.Selected("selection"),
    ) = useCase(scope, deviceId, conversationId, roomId, limit)

    fun background(): CollaborationOperationScope.Background {
        val now = local.setup.now
        return CollaborationOperationScope.Background(
            CollaborationBackgroundAdmission(
                CollaborationAdmissionObservation(owner, deviceId, 1, 1, 1, now, now + 20.seconds),
                now,
                now + 20.seconds,
            )
        )
    }
}
