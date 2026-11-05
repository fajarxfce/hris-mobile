package dev.fajar.starter.collaboration.data.identity

import dev.fajar.starter.collaboration.data.dto.*
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

internal fun spaceIdentityCommandScope(access: CollaborationSpaceLocalAccess): CommandScope {
    require(
        validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        )
    )
    require(validCollaborationMessageId(access.spaceId))
    return CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
}

internal fun spaceIdentityCommandPartition(access: CollaborationSpaceLocalAccess): String {
    spaceIdentityCommandScope(access)
    return "hris.collaboration.space-identity/v1/" + access.spaceId
}

internal fun CollaborationSpaceIdentityCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

internal class CollaborationSpaceIdentityCommandCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun request(command: CollaborationSpaceIdentityCommand): String {
        require(validCollaborationSpaceIdentityCommand(access, command))
        val b = command.binding
        return seal(
            command.id,
            "request",
            0,
            Json.encodeToString(
                IdentityRequest(
                    1,
                    command.id,
                    b.audience,
                    b.accountId,
                    b.spaceId,
                    b.nativeSessionId,
                    command.platform.name,
                    command.clientBuild,
                    command.createdAt.toString(),
                )
            ),
        )
    }

    suspend fun outcome(
        command: CollaborationSpaceIdentityCommand,
        revision: Long,
        outcome: CollaborationSpaceIdentityOutcome,
        attempts: Int,
    ): String {
        require(
            validCollaborationSpaceIdentityRecord(
                access,
                CollaborationSpaceIdentityCommandRecord(command, revision, attempts, outcome),
            )
        )
        val state =
            when (outcome) {
                CollaborationSpaceIdentityOutcome.Pending -> IdentityOutcome(1, "PENDING", attempts)
                is CollaborationSpaceIdentityOutcome.Unconfirmed ->
                    IdentityOutcome(
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
                is CollaborationSpaceIdentityOutcome.Selected ->
                    IdentityOutcome(
                        1,
                        "SELECTED",
                        attempts,
                        receipt =
                            CollaborationReceiptDto(outcome.receipt.id, outcome.receipt.version),
                    )
            }
        state.domain()
        return seal(command.id, "outcome", revision, Json.encodeToString(state))
    }

    suspend fun decode(raw: CommandRecord): CollaborationSpaceIdentityCommandRecord {
        require(
            raw.partition == spaceIdentityCommandPartition(access) && raw.resourceId == "identity"
        )
        require(validCollaborationMessageId(raw.id) && raw.revision in 0..9_007_199_254_740_991L)
        val request =
            Json.decodeFromString<IdentityRequest>(open(raw.id, "request", 0, raw.request))
        require(request.schema == 1)
        val command =
            CollaborationSpaceIdentityCommand(
                request.id,
                CollaborationSpaceIdentityBinding(
                    request.audience,
                    request.accountId,
                    request.spaceId,
                    request.nativeSessionId,
                ),
                CollaborationNativePlatform.valueOf(request.platform),
                request.clientBuild,
                Instant.parse(request.createdAt),
            )
        require(validCollaborationSpaceIdentityCommand(access, command))
        require(command.id == raw.id && raw.sortKey == command.orderKey())
        val state =
            Json.decodeFromString<IdentityOutcome>(open(raw.id, "outcome", raw.revision, raw.state))
        return CollaborationSpaceIdentityCommandRecord(
                command,
                raw.revision,
                state.attempts,
                state.domain(),
            )
            .also { require(validCollaborationSpaceIdentityRecord(access, it)) }
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
                    "hris-collaboration-space-identity-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    id,
                    "identity",
                    purpose,
                    revision.toString(),
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class IdentityRequest(
    val schema: Int,
    val id: String,
    val audience: String,
    val accountId: String,
    val spaceId: String,
    val nativeSessionId: String,
    val platform: String,
    val clientBuild: Long,
    val createdAt: String,
)

@Serializable
private data class IdentityOutcome(
    val schema: Int,
    val phase: String,
    val attempts: Int,
    val failure: CollaborationSetupFailureDto? = null,
    val receipt: CollaborationReceiptDto? = null,
) {
    fun domain(): CollaborationSpaceIdentityOutcome {
        require(schema == 1 && attempts in 0..1_000)
        return when (phase) {
            "PENDING" -> {
                require(attempts == 0 && failure == null && receipt == null)
                CollaborationSpaceIdentityOutcome.Pending
            }
            "UNCONFIRMED" -> {
                require(attempts > 0 && receipt == null)
                CollaborationSpaceIdentityOutcome.Unconfirmed(
                    failure?.let {
                        require(it.code == null || it.code.matches(Regex("[A-Za-z0-9_.:-]{1,100}")))
                        require(it.retryAfterSeconds == null || it.retryAfterSeconds in 0..86_400)
                        Failure(
                            FailureKind.valueOf(it.kind),
                            "Identity selection could not be confirmed.",
                            code = it.code,
                            retryAfterSeconds = it.retryAfterSeconds,
                        )
                    }
                )
            }
            "SELECTED" -> {
                require(attempts > 0 && failure == null)
                val value = requireNotNull(receipt)
                CollaborationSpaceIdentityOutcome.Selected(
                    CollaborationSetupReceipt(value.id, value.version)
                )
            }
            else -> error("Unsupported identity selection outcome.")
        }
    }
}
