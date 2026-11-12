package dev.fajar.starter.collaboration.data.recovery

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val INCOMING_DEMAND_DOCUMENT = "demand"
internal const val INCOMING_DEMAND_CHECKPOINT = "incoming-demand/v1"
internal const val INCOMING_DEMAND_PARTS_CHECKPOINT = "incoming-demand/v3"
internal const val INCOMING_DEMAND_TARGETS_CHECKPOINT = "incoming-demand/v4"
internal const val INCOMING_DEMAND_INVENTORY_CHECKPOINT = "incoming-demand/v5"
internal const val INCOMING_DEMAND_REQUESTED_INVENTORY_CHECKPOINT = "incoming-demand/v6"
internal const val INCOMING_DEMAND_HISTORY_RECOVERY_CHECKPOINT = "incoming-demand/v7"
internal const val INCOMING_DEMAND_TARGETED_HISTORY_CHECKPOINT = "incoming-demand/v8"
internal const val INCOMING_DEMAND_ROOM_KEY_RECEIPT_CHECKPOINT = "incoming-demand/v9"
// One header, at most 64 room parts and 64 source parts, plus an over-capacity sentinel.
internal const val INCOMING_DEMAND_READ_LIMIT = 130

internal fun incomingDemandScope(access: CollaborationSpaceLocalAccess): ReplicaScope {
    require(
        validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        )
    )
    require(validCollaborationMessageId(access.spaceId))
    return recoveryScope(
        CollaborationRecoveryAccess(access.sessionId, access.audience, access.accountId)
    )
}

internal fun incomingDemandPartition(access: CollaborationSpaceLocalAccess): String {
    incomingDemandScope(access)
    return COLLABORATION_HISTORY_PREFIX + "space-incoming-demand/v1/" + access.spaceId
}

/** Authenticated scheduling facts; one complete snapshot and wake share the replica transaction. */
internal class CollaborationSpaceIncomingDemandCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(page: ReplicaPage): CollaborationSpaceIncomingDemandSnapshot {
        val raw = requireNotNull(page.checkpoint)
        require(
            raw.payload in
                setOf(
                    INCOMING_DEMAND_CHECKPOINT,
                    INCOMING_DEMAND_PARTS_CHECKPOINT,
                    INCOMING_DEMAND_TARGETS_CHECKPOINT,
                    INCOMING_DEMAND_INVENTORY_CHECKPOINT,
                    INCOMING_DEMAND_REQUESTED_INVENTORY_CHECKPOINT,
                    INCOMING_DEMAND_HISTORY_RECOVERY_CHECKPOINT,
                    INCOMING_DEMAND_TARGETED_HISTORY_CHECKPOINT,
                    INCOMING_DEMAND_ROOM_KEY_RECEIPT_CHECKPOINT,
                )
        )
        require(
            raw.published && raw.revision == 0L && raw.handle.scope == incomingDemandScope(access)
        )
        require(raw.handle.partition == incomingDemandPartition(access))
        require(validCollaborationMessageId(raw.handle.generation))
        require(page.documents.size in 1 until INCOMING_DEMAND_READ_LIMIT)
        val documents = page.documents.toList()
        require(documents.map { it.id }.distinct().size == documents.size)
        require(documents.sumOf { it.payload?.length?.toLong() ?: 0L } <= 4_194_304L)
        documents.forEach {
            require(it.version == 0L && it.sortKey == it.id && it.tags.isEmpty())
            require(requireNotNull(it.payload).length in 1..131072)
        }
        val header = documents.single { it.id == INCOMING_DEMAND_DOCUMENT }
        val value =
            if (raw.payload == INCOMING_DEMAND_CHECKPOINT) {
                require(documents.size == 1)
                val dto = decodePart<IncomingDemandDto>(raw.handle.generation, header)
                require(dto.schema in 1..2 && dto.rooms.size <= 64)
                require(dto.rooms.none { it.sourceId != null })
                require(
                    dto.schema == 2 || (dto.scan == null && dto.rooms.none { it.retry != null })
                )
                CollaborationSpaceIncomingDemand(
                    position(dto.nextPosition),
                    dto.rooms.map { it.toDemand() },
                    dto.rescanGeneration,
                    dto.scan?.toScan(),
                )
            } else {
                decodeParts(raw, documents)
            }
        require(validCollaborationSpaceIncomingDemand(access, value))
        return CollaborationSpaceIncomingDemandSnapshot(raw.handle.generation, value)
    }

    /** Versioned multipart payload mapping, after the common replica envelope has been verified. */
    private suspend fun decodeParts(
        raw: ReplicaCheckpoint,
        documents: List<ReplicaDocument>,
    ): CollaborationSpaceIncomingDemand {
        val header = documents.single { it.id == INCOMING_DEMAND_DOCUMENT }
        val dto = decodePart<IncomingDemandPartsDto>(raw.handle.generation, header)
        val keys = raw.payload == INCOMING_DEMAND_ROOM_KEY_RECEIPT_CHECKPOINT
        require(keys == (dto.roomKeyReceipt != null))
        val targeted =
            raw.payload == INCOMING_DEMAND_TARGETED_HISTORY_CHECKPOINT ||
                (keys &&
                    (dto.historyRecovery?.roomIds != null ||
                        dto.historyRecovery?.requestedRoomIds != null))
        val recovery =
            keys || targeted || raw.payload == INCOMING_DEMAND_HISTORY_RECOVERY_CHECKPOINT
        val requested = recovery || raw.payload == INCOMING_DEMAND_REQUESTED_INVENTORY_CHECKPOINT
        require(recovery == (dto.historyRecovery != null))
        require(
            targeted ==
                (dto.historyRecovery?.roomIds != null ||
                    dto.historyRecovery?.requestedRoomIds != null)
        )
        val inventory = requested || raw.payload == INCOMING_DEMAND_INVENTORY_CHECKPOINT
        val typed = inventory || raw.payload == INCOMING_DEMAND_TARGETS_CHECKPOINT
        require(
            dto.schema ==
                (if (keys) 9
                else if (targeted) 8
                else if (recovery) 7
                else if (requested) 6 else if (inventory) 5 else if (typed) 4 else 3) &&
                dto.roomCount in 0..64
        )
        require(dto.physicalCount in 0..(64 - dto.roomCount) && dto.sourceCount in 0..64)
        val expected =
            listOf(INCOMING_DEMAND_DOCUMENT) +
                (0 until dto.roomCount).map { partId("r", it) } +
                (0 until dto.physicalCount).map { partId("p", it) } +
                (0 until dto.sourceCount).map { partId("s", it) }
        require(documents.map { it.id }.toSet() == expected.toSet())
        val indexed = documents.associateBy { it.id }
        return CollaborationSpaceIncomingDemand(
            position(dto.nextPosition),
            (0 until dto.roomCount).map {
                val document = indexed.getValue(partId("r", it))
                if (typed)
                    decodePart<IncomingTargetRoomDemandDto>(raw.handle.generation, document)
                        .toDemand()
                else decodePart<IncomingRoomDemandDto>(raw.handle.generation, document).toDemand()
            },
            dto.rescanGeneration,
            dto.scan?.toScan(),
            (0 until dto.sourceCount).map {
                decodePart<IncomingSourceProgressDto>(
                        raw.handle.generation,
                        indexed.getValue(partId("s", it)),
                    )
                    .toProgress(inventory, requested)
            },
            (0 until dto.physicalCount).map {
                decodePart<IncomingPhysicalRoomDto>(
                        raw.handle.generation,
                        indexed.getValue(partId("p", it)),
                    )
                    .toDemand()
            },
            dto.historyRecovery?.toRecovery(),
            dto.roomKeyReceipt?.toReceipt(),
        )
    }

    suspend fun encode(snapshot: CollaborationSpaceIncomingDemandSnapshot): ReplicaSnapshot {
        require(validCollaborationMessageId(snapshot.generation))
        val stable = requireNotNull(captureCollaborationSpaceIncomingDemandSnapshot(snapshot))
        val value = stable.demand
        val keys = value.roomKeyReceipt != null
        require(validCollaborationSpaceIncomingDemand(access, value))
        val targeted =
            value.historyRecovery?.let { it.roomIds != null || it.requestedRoomIds != null } == true
        val recovery = value.historyRecovery != null
        val requested = recovery || value.sources.any { it.inventoryRequestedGeneration != null }
        val inventory =
            requested ||
                value.sources.any { it.inventoryAfterRoomId != null || it.inventoryRetry != null }
        val typed =
            inventory || value.rooms.any { it.target is CollaborationSpaceIncomingTarget.Original }
        val rooms = if (typed) emptyList() else value.rooms.map { it.toDto() }
        val scan = value.scan?.toDto()
        if (!typed && value.sources.isEmpty() && value.physicalRooms.isEmpty()) {
            return ReplicaSnapshot(
                snapshot.generation,
                INCOMING_DEMAND_CHECKPOINT,
                listOf(
                    encodePart(
                        snapshot.generation,
                        INCOMING_DEMAND_DOCUMENT,
                        IncomingDemandDto(
                            if (value.scan == null && value.rooms.none { it.retry != null }) 1
                            else 2,
                            value.nextPosition.toString(),
                            rooms,
                            value.rescanGeneration,
                            scan,
                        ),
                    )
                ),
            )
        }
        val parts =
            mutableListOf(
                encodePart(
                    snapshot.generation,
                    INCOMING_DEMAND_DOCUMENT,
                    IncomingDemandPartsDto(
                        if (keys) 9
                        else if (targeted) 8
                        else if (recovery) 7
                        else if (requested) 6 else if (inventory) 5 else if (typed) 4 else 3,
                        value.nextPosition.toString(),
                        value.rooms.size,
                        value.physicalRooms.size,
                        value.sources.size,
                        value.rescanGeneration,
                        scan,
                        value.historyRecovery?.toDto(),
                        value.roomKeyReceipt?.toDto(),
                    ),
                )
            )
        value.rooms.forEachIndexed { index, room ->
            parts +=
                if (typed) encodePart(snapshot.generation, partId("r", index), room.toTargetDto())
                else encodePart(snapshot.generation, partId("r", index), rooms[index])
        }
        value.physicalRooms.forEachIndexed { index, room ->
            parts += encodePart(snapshot.generation, partId("p", index), room.toDto())
        }
        value.sources.forEachIndexed { index, progress ->
            parts += encodePart(snapshot.generation, partId("s", index), progress.toDto())
        }
        val result =
            ReplicaSnapshot(
                snapshot.generation,
                if (keys) INCOMING_DEMAND_ROOM_KEY_RECEIPT_CHECKPOINT
                else if (targeted) INCOMING_DEMAND_TARGETED_HISTORY_CHECKPOINT
                else if (recovery) INCOMING_DEMAND_HISTORY_RECOVERY_CHECKPOINT
                else if (requested) INCOMING_DEMAND_REQUESTED_INVENTORY_CHECKPOINT
                else if (inventory) INCOMING_DEMAND_INVENTORY_CHECKPOINT
                else if (typed) INCOMING_DEMAND_TARGETS_CHECKPOINT
                else INCOMING_DEMAND_PARTS_CHECKPOINT,
                parts.sortedBy { it.id },
            )
        validateReplicaReplacement(
            incomingDemandScope(access),
            incomingDemandPartition(access),
            null,
            result,
        )
        return result
    }

    private suspend inline fun <reified T> decodePart(
        generation: String,
        document: ReplicaDocument,
    ): T {
        val bytes =
            cipher.decrypt(
                key,
                aad(generation, document.id),
                Base64.decode(requireNotNull(document.payload)),
            )
        return try {
            require(bytes.size in 1..65536)
            Json.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true))
        } finally {
            bytes.fill(0)
        }
    }

    private suspend inline fun <reified T> encodePart(
        generation: String,
        id: String,
        value: T,
    ): ReplicaDocument {
        val bytes = Json.encodeToString(value).encodeToByteArray(throwOnInvalidSequence = true)
        val payload =
            try {
                require(bytes.size in 1..65536)
                Base64.encode(cipher.encrypt(key, aad(generation, id), bytes)).also {
                    require(it.length <= 131072)
                }
            } finally {
                bytes.fill(0)
            }
        return ReplicaDocument(id, 0, id, payload)
    }

    private fun CollaborationSpaceIncomingPhysicalRoomDemand.toDto() =
        IncomingPhysicalRoomDto(
            sourceId,
            roomId,
            generation,
            position.toString(),
            headEventId,
            membership.name,
            limited,
            retry?.toDto(),
        )

    private fun CollaborationSpaceRoomKeyReceipt.toDto() =
        IncomingRoomKeyReceiptDto(
            source.storeId,
            source.matrixUserId,
            source.matrixDeviceId,
            runtimeDeviceId,
            runtimeOperationId,
            receiptId,
        )

    private fun IncomingRoomKeyReceiptDto.toReceipt() =
        CollaborationSpaceRoomKeyReceipt(
            CollaborationSpaceIncomingSource(storeId, matrixUserId, matrixDeviceId),
            runtimeDeviceId,
            runtimeOperationId,
            receiptId,
        )

    private fun CollaborationSpaceHistoryRecovery.toDto() =
        IncomingHistoryRecoveryDto(
            generation,
            revision.toString(),
            afterPartition,
            moreHistoryPages,
            unresolved,
            complete,
            requestedGeneration,
            retry?.toDto(),
            roomIds,
            requestedRoomIds,
        )

    private fun IncomingHistoryRecoveryDto.toRecovery() =
        CollaborationSpaceHistoryRecovery(
            generation,
            position(revision),
            afterPartition,
            moreHistoryPages,
            unresolved,
            complete,
            requestedGeneration,
            retry?.toRetry(),
            roomIds,
            requestedRoomIds,
        )

    private fun CollaborationSpaceIncomingRoomDemand.toDto() =
        IncomingRoomDemandDto(
            (target as CollaborationSpaceIncomingTarget.Published).conversationId,
            generation,
            position.toString(),
            headEventId,
            phase.name,
            retry?.toDto(),
            sourceId,
        )

    private fun CollaborationSpaceIncomingRoomDemand.toTargetDto() =
        IncomingTargetRoomDemandDto(
            when (val destination = target) {
                is CollaborationSpaceIncomingTarget.Published ->
                    IncomingRoomTargetDto.Published(destination.conversationId)
                is CollaborationSpaceIncomingTarget.Original ->
                    IncomingRoomTargetDto.Original(
                        destination.position.companyId,
                        destination.position.conversationId,
                    )
            },
            generation,
            position.toString(),
            headEventId,
            phase.name,
            retry?.toDto(),
            sourceId,
        )

    private fun IncomingTargetRoomDemandDto.toDemand() =
        CollaborationSpaceIncomingRoomDemand(
            when (val destination = target) {
                is IncomingRoomTargetDto.Published ->
                    CollaborationSpaceIncomingTarget.Published(destination.conversationId)
                is IncomingRoomTargetDto.Original ->
                    CollaborationSpaceIncomingTarget.Original(
                        CollaborationOriginalConversationPosition(
                            destination.companyId,
                            destination.conversationId,
                        )
                    )
            },
            generation,
            position(position),
            headEventId,
            CollaborationSpaceIncomingPhase.valueOf(phase),
            retry?.toRetry(),
            sourceId,
        )

    private fun CollaborationSpaceIncomingScan.toDto() =
        IncomingScanDto(
            generation,
            cursor?.let {
                IncomingScanCursorDto(
                    it.boundary.generation,
                    it.boundary.revision.toString(),
                    it.afterId,
                )
            },
            hasNewerBindings,
        )

    private fun IncomingRoomDemandDto.toDemand() =
        CollaborationSpaceIncomingRoomDemand(
            conversationId,
            generation,
            position(position),
            headEventId,
            CollaborationSpaceIncomingPhase.valueOf(phase),
            retry?.toRetry(),
            sourceId,
        )

    private fun IncomingPhysicalRoomDto.toDemand() =
        CollaborationSpaceIncomingPhysicalRoomDemand(
            sourceId,
            roomId,
            generation,
            position(position),
            headEventId,
            CollaborationSpaceIncomingMembership.valueOf(membership),
            limited,
            retry?.toRetry(),
        )

    private fun CollaborationSpaceIncomingSourceProgress.toDto(): IncomingSourceProgressDto {
        // Base64 bounds JSON escaping while preserving the provider's exact opaque UTF-8.
        val bytes = position.encodeToByteArray(throwOnInvalidSequence = true)
        val encoded =
            try {
                Base64.encode(bytes)
            } finally {
                bytes.fill(0)
            }
        return IncomingSourceProgressDto(
            source.storeId,
            source.matrixUserId,
            source.matrixDeviceId,
            encoded,
            inventoryGeneration,
            inventoryAfterRoomId,
            inventoryRetry?.toDto(),
            inventoryRequestedGeneration,
        )
    }

    private fun IncomingSourceProgressDto.toProgress(
        inventory: Boolean,
        requested: Boolean,
    ): CollaborationSpaceIncomingSourceProgress {
        require(inventory || (inventoryAfterRoomId == null && inventoryRetry == null))
        require(requested || inventoryRequestedGeneration == null)
        require(position.length in 1..5464)
        val bytes = Base64.decode(position)
        val decoded =
            try {
                require(bytes.size in 1..4096 && Base64.encode(bytes) == position)
                bytes.decodeToString(throwOnInvalidSequence = true)
            } finally {
                bytes.fill(0)
            }
        return CollaborationSpaceIncomingSourceProgress(
            CollaborationSpaceIncomingSource(storeId, matrixUserId, matrixDeviceId),
            decoded,
            inventoryGeneration,
            inventoryAfterRoomId,
            inventoryRetry?.toRetry(),
            inventoryRequestedGeneration,
        )
    }

    private fun IncomingScanDto.toScan() =
        CollaborationSpaceIncomingScan(
            generation,
            cursor?.let {
                CollaborationSpaceRoomBindingCursor(
                    CollaborationSpaceRoomBindingBoundary(
                        access.audience,
                        access.accountId,
                        access.spaceId,
                        it.generation,
                        position(it.revision),
                    ),
                    it.afterId,
                )
            },
            hasNewerBindings,
        )

    private fun IncomingRetryDto.toRetry() =
        CollaborationSpaceIncomingRetry(position(attemptedAt), position(eligibleAt), failures)

    private fun CollaborationSpaceIncomingRetry.toDto() =
        IncomingRetryDto(
            attemptedAtEpochMillis.toString(),
            eligibleAtEpochMillis.toString(),
            failures,
        )

    private fun position(value: String): Long {
        require(value.length in 1..16)
        return value.toLong().also { require(it.toString() == value) }
    }

    private fun aad(generation: String, document: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-space-incoming-demand-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    generation,
                    document,
                    "0",
                )
            )
            .encodeToByteArray()

    private fun partId(prefix: String, index: Int) =
        prefix + "-" + index.toString().padStart(2, '0')
}

@Serializable
private data class IncomingDemandDto(
    val schema: Int,
    val nextPosition: String,
    val rooms: List<IncomingRoomDemandDto>,
    val rescanGeneration: String?,
    val scan: IncomingScanDto? = null,
)

@Serializable
private data class IncomingDemandPartsDto(
    val schema: Int,
    val nextPosition: String,
    val roomCount: Int,
    val physicalCount: Int,
    val sourceCount: Int,
    val rescanGeneration: String?,
    val scan: IncomingScanDto? = null,
    val historyRecovery: IncomingHistoryRecoveryDto? = null,
    val roomKeyReceipt: IncomingRoomKeyReceiptDto? = null,
)

@Serializable
private data class IncomingRoomKeyReceiptDto(
    val storeId: String,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val runtimeDeviceId: String,
    val runtimeOperationId: String,
    val receiptId: String,
)

@Serializable
private data class IncomingHistoryRecoveryDto(
    val generation: String,
    val revision: String,
    val afterPartition: String?,
    val moreHistoryPages: Boolean,
    val unresolved: Boolean,
    val complete: Boolean,
    val requestedGeneration: String?,
    val retry: IncomingRetryDto?,
    val roomIds: List<String>? = null,
    val requestedRoomIds: List<String>? = null,
)

@Serializable
private data class IncomingRoomDemandDto(
    val conversationId: String,
    val generation: String,
    val position: String,
    val headEventId: String?,
    val phase: String,
    val retry: IncomingRetryDto? = null,
    val sourceId: String? = null,
)

@Serializable
private sealed interface IncomingRoomTargetDto {
    @Serializable
    @SerialName("published")
    data class Published(val conversationId: String) : IncomingRoomTargetDto

    @Serializable
    @SerialName("original")
    data class Original(val companyId: String, val conversationId: String) : IncomingRoomTargetDto
}

@Serializable
private data class IncomingTargetRoomDemandDto(
    val target: IncomingRoomTargetDto,
    val generation: String,
    val position: String,
    val headEventId: String?,
    val phase: String,
    val retry: IncomingRetryDto? = null,
    val sourceId: String? = null,
)

@Serializable
private data class IncomingPhysicalRoomDto(
    val sourceId: String,
    val roomId: String,
    val generation: String,
    val position: String,
    val headEventId: String?,
    val membership: String,
    val limited: Boolean,
    val retry: IncomingRetryDto? = null,
)

@Serializable
private data class IncomingSourceProgressDto(
    val storeId: String,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val position: String,
    val inventoryGeneration: String?,
    val inventoryAfterRoomId: String? = null,
    val inventoryRetry: IncomingRetryDto? = null,
    val inventoryRequestedGeneration: String? = null,
)

@Serializable
private data class IncomingScanDto(
    val generation: String,
    val cursor: IncomingScanCursorDto?,
    val hasNewerBindings: Boolean,
)

@Serializable
private data class IncomingScanCursorDto(
    val generation: String,
    val revision: String,
    val afterId: String,
)

@Serializable
private data class IncomingRetryDto(
    @SerialName("at") val attemptedAt: String,
    @SerialName("after") val eligibleAt: String,
    @SerialName("n") val failures: Int,
)
