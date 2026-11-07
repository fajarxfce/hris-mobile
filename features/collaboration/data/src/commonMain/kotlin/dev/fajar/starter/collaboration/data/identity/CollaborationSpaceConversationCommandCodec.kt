package dev.fajar.starter.collaboration.data.identity

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.requireOriginalRoomRequestIntent
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun spaceConversationCommandScope(access: CollaborationSpaceLocalAccess): CommandScope {
    require(
        validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        )
    )
    require(validCollaborationSpaceConversationId(access.accountId))
    require(validCollaborationSpaceConversationId(access.spaceId))
    return CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
}

internal fun spaceConversationCommandPartition(access: CollaborationSpaceLocalAccess): String {
    spaceConversationCommandScope(access)
    return "hris.collaboration.space-conversation/v1/" + access.spaceId
}

internal fun CollaborationSpaceConversationCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

internal fun CollaborationSpaceConversationCommand.resourceId() =
    when (this) {
        is CollaborationSpaceConversationCommand.Open -> "open/" + peerAccountId
        is CollaborationSpaceConversationCommand.Room -> "room/" + intent.conversationId
    }

internal class CollaborationSpaceConversationCommandCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun request(command: CollaborationSpaceConversationCommand): String {
        require(validCollaborationSpaceConversationCommand(access, command))
        val b = command.binding
        val predecessor = (command as? CollaborationSpaceConversationCommand.Room)?.predecessorId
        return seal(
            command.id,
            "request",
            0,
            Json.encodeToString(
                ConversationRequest(
                    if (predecessor == null) 1 else 2,
                    command.id,
                    b.audience,
                    b.accountId,
                    b.spaceId,
                    b.nativeSessionId,
                    command.createdAt.toString(),
                    command.peerAccountId,
                    if (command is CollaborationSpaceConversationCommand.Open) "OPEN" else "ROOM",
                    (command as? CollaborationSpaceConversationCommand.Room)
                        ?.intent
                        ?.conversationId,
                    (command as? CollaborationSpaceConversationCommand.Room)?.intent?.request?.let {
                        ConversationRoomRequest(
                            it.deviceId,
                            it.expectedDeviceVersion,
                            it.expectedAttempt?.revision,
                            it.expectedAttempt?.accountId,
                        )
                    },
                    predecessorId = predecessor,
                )
            ),
        )
    }

    suspend fun outcome(
        command: CollaborationSpaceConversationCommand,
        revision: Long,
        outcome: CollaborationSpaceConversationOutcome,
        attempts: Int,
    ): String {
        require(
            validCollaborationSpaceConversationCommandRecord(
                access,
                CollaborationSpaceConversationCommandRecord(command, revision, attempts, outcome),
            )
        )
        val state =
            when (outcome) {
                CollaborationSpaceConversationOutcome.Pending ->
                    ConversationOutcome(1, "PENDING", attempts)
                is CollaborationSpaceConversationOutcome.Unconfirmed ->
                    ConversationOutcome(
                        1,
                        "UNCONFIRMED",
                        attempts,
                        failure =
                            outcome.failure?.let {
                                CollaborationSetupFailureDto(
                                    it.kind.name,
                                    it.code,
                                    it.retryAfterSeconds,
                                )
                            },
                    )
                is CollaborationSpaceConversationOutcome.Recorded ->
                    ConversationOutcome(
                        1,
                        "RECORDED",
                        attempts,
                        receipt =
                            CollaborationReceiptDto(outcome.receipt.id, outcome.receipt.version),
                    )
                is CollaborationSpaceConversationOutcome.Fenced -> {
                    val proof = outcome.evidence
                    val original = proof.original
                    ConversationOutcome(
                        2,
                        "FENCED",
                        attempts,
                        fence =
                            ConversationFenceEvidence(
                                original.binding.accountId,
                                CollaborationSpaceRoomRequestIntentDto(
                                    original.operationId,
                                    original.binding.spaceId,
                                    original.conversationId,
                                    original.request.deviceId,
                                    original.request.expectedDeviceVersion,
                                    original.request.expectedAttempt?.revision,
                                    original.request.expectedAttempt?.accountId,
                                ),
                                proof.id,
                                proof.createdAt.toString(),
                            ),
                    )
                }
            }
        state.domain(command)
        return seal(command.id, "outcome", revision, Json.encodeToString(state))
    }

    suspend fun decode(raw: CommandRecord): CollaborationSpaceConversationCommandRecord {
        require(raw.partition == spaceConversationCommandPartition(access))
        require(
            validCollaborationSpaceConversationId(raw.id) &&
                raw.revision in 0..9_007_199_254_740_991L
        )
        val request =
            Json.decodeFromString<ConversationRequest>(open(raw.id, "request", 0, raw.request))
        require(request.schema in 1..2)
        val binding =
            CollaborationSpaceIdentityBinding(
                request.audience,
                request.accountId,
                request.spaceId,
                request.nativeSessionId,
            )
        val createdAt = Instant.parse(request.createdAt)
        val command =
            when (request.kind) {
                "OPEN" -> {
                    require(
                        request.schema == 1 &&
                            request.predecessorId == null &&
                            request.conversationId == null &&
                            request.room == null
                    )
                    CollaborationSpaceConversationCommand.Open(
                        request.id,
                        binding,
                        request.peerAccountId,
                        createdAt,
                    )
                }
                "ROOM" -> {
                    require(
                        (request.schema == 1 && request.predecessorId == null) ||
                            (request.schema == 2 && request.predecessorId != null)
                    )
                    val room = requireNotNull(request.room)
                    require(
                        (room.expectedRequestRevision == null) ==
                            (room.expectedRequestAccountId == null)
                    )
                    val attempt =
                        room.expectedRequestRevision?.let {
                            CollaborationSpaceRoomAttempt(
                                requireNotNull(room.expectedRequestAccountId),
                                it,
                            )
                        }
                    CollaborationSpaceConversationCommand.Room(
                        CollaborationSpaceRoomRequestIntent(
                            request.id,
                            binding,
                            requireNotNull(request.conversationId),
                            CollaborationSpaceRoomRequest(
                                room.deviceId,
                                room.expectedDeviceVersion,
                                attempt,
                            ),
                        ),
                        request.peerAccountId,
                        createdAt,
                        request.predecessorId,
                    )
                }
                else -> error("Unsupported conversation command.")
            }
        require(validCollaborationSpaceConversationCommand(access, command))
        require(
            command.id == raw.id &&
                raw.sortKey == command.orderKey() &&
                raw.resourceId == command.resourceId()
        )
        val state =
            Json.decodeFromString<ConversationOutcome>(
                open(raw.id, "outcome", raw.revision, raw.state)
            )
        return CollaborationSpaceConversationCommandRecord(
                command,
                raw.revision,
                state.attempts,
                state.domain(command),
            )
            .also { require(validCollaborationSpaceConversationCommandRecord(access, it)) }
    }

    suspend fun handoff(
        expected: CollaborationSpaceConversationCommandRecord,
        successorId: String,
    ): String {
        require(
            validCollaborationSpaceConversationCommandRecord(
                access,
                expected.copy(successorId = successorId),
            )
        )
        return seal(
            expected.command.id,
            "successor",
            expected.revision,
            Json.encodeToString(ConversationHandoff(1, "ROOM_REPLACEMENT_PREPARED", successorId)),
        )
    }

    suspend fun successor(raw: CommandRecord, evidence: String): String {
        val link =
            Json.decodeFromString<ConversationHandoff>(
                open(raw.id, "successor", raw.revision, evidence)
            )
        require(
            link.schema == 1 &&
                link.kind == "ROOM_REPLACEMENT_PREPARED" &&
                validCollaborationSpaceConversationId(link.successorId) &&
                link.successorId != raw.id
        )
        return link.successorId
    }

    private suspend fun seal(id: String, purpose: String, revision: Long, value: String): String {
        val bytes = value.encodeToByteArray()
        return try {
            require(bytes.size in 1..4096)
            Base64.encode(cipher.encrypt(key, aad(id, purpose, revision), bytes)).also {
                require(it.length <= 8192)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(id: String, purpose: String, revision: Long, value: String): String {
        require(value.length in 1..8192)
        val bytes = cipher.decrypt(key, aad(id, purpose, revision), Base64.decode(value))
        return try {
            require(bytes.size in 1..4096)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(id: String, purpose: String, revision: Long) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-space-conversation-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    id,
                    "conversation-setup",
                    purpose,
                    revision.toString(),
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class ConversationRequest(
    val schema: Int,
    val id: String,
    val audience: String,
    val accountId: String,
    val spaceId: String,
    val nativeSessionId: String,
    val createdAt: String,
    val peerAccountId: String,
    val kind: String,
    val conversationId: String? = null,
    val room: ConversationRoomRequest? = null,
    val predecessorId: String? = null,
)

@Serializable
private data class ConversationOutcome(
    val schema: Int,
    val phase: String,
    val attempts: Int,
    val failure: CollaborationSetupFailureDto? = null,
    val receipt: CollaborationReceiptDto? = null,
    val fence: ConversationFenceEvidence? = null,
) {
    fun domain(
        command: CollaborationSpaceConversationCommand
    ): CollaborationSpaceConversationOutcome {
        require(schema in 1..2 && attempts in 0..1_000)
        return when (phase) {
            "PENDING" -> {
                require(
                    schema == 1 &&
                        attempts == 0 &&
                        failure == null &&
                        receipt == null &&
                        fence == null
                )
                CollaborationSpaceConversationOutcome.Pending
            }
            "UNCONFIRMED" -> {
                require(schema == 1 && attempts > 0 && receipt == null && fence == null)
                CollaborationSpaceConversationOutcome.Unconfirmed(
                    failure?.let {
                        require(it.code == null || it.code.matches(Regex("[A-Za-z0-9_.:-]{1,100}")))
                        require(it.retryAfterSeconds == null || it.retryAfterSeconds in 0..86_400)
                        Failure(
                            FailureKind.valueOf(it.kind),
                            "Conversation setup could not be confirmed.",
                            code = it.code,
                            retryAfterSeconds = it.retryAfterSeconds,
                        )
                    }
                )
            }
            "RECORDED" -> {
                require(schema == 1 && attempts > 0 && failure == null && fence == null)
                val value = requireNotNull(receipt)
                CollaborationSpaceConversationOutcome.Recorded(
                    CollaborationSetupReceipt(value.id, value.version)
                )
            }
            "FENCED" -> {
                require(schema == 2 && attempts > 0 && failure == null && receipt == null)
                val room =
                    command as? CollaborationSpaceConversationCommand.Room
                        ?: error("Only a Room request can retain a fence.")
                val proof = requireNotNull(fence)
                require(proof.accountId == room.binding.accountId)
                proof.original.requireOriginalRoomRequestIntent(room.intent)
                CollaborationSpaceConversationOutcome.Fenced(
                    CollaborationSpaceRoomRequestFence(
                        room.intent,
                        proof.id,
                        Instant.parse(proof.createdAt),
                    )
                )
            }
            else -> error("Unsupported conversation setup outcome.")
        }
    }
}

@Serializable
private data class ConversationFenceEvidence(
    val accountId: String,
    val original: CollaborationSpaceRoomRequestIntentDto,
    val id: String,
    val createdAt: String,
)

@Serializable
private data class ConversationRoomRequest(
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedRequestRevision: Long? = null,
    val expectedRequestAccountId: String? = null,
)

@Serializable
private data class ConversationHandoff(val schema: Int, val kind: String, val successorId: String)
