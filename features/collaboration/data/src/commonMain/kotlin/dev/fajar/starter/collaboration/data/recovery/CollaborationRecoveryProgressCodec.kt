package dev.fajar.starter.collaboration.data.recovery

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val COMPANY_RECOVERY_PARTITION = COLLABORATION_HISTORY_PREFIX + "company-recovery"

internal fun recoveryScope(access: CollaborationRecoveryAccess): ReplicaScope {
    require(access.sessionId.length in 1..128 && access.sessionId.all { it.code in 33..126 })
    require(access.audience.length in 1..2048 && validCollaborationMessageId(access.accountId))
    return ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
}

internal class CollaborationRecoveryProgressCodec(
    private val access: CollaborationRecoveryAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationRecoveryProgress {
        require(raw.published && raw.revision == 0L && raw.handle.scope == recoveryScope(access))
        require(raw.handle.partition == COMPANY_RECOVERY_PARTITION)
        require(validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..2048)
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
        return try {
            require(bytes.size in 1..1024)
            val dto =
                Json.decodeFromString<ProgressDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            require(dto.schema in 1..2)
            require(dto.schema != 1 || dto.sweep?.continuationRequired != true)
            CollaborationRecoveryProgress(
                    raw.handle.generation,
                    dto.sweep?.let {
                        CollaborationRecoverySweep(
                            it.wakeGeneration,
                            it.afterCompanyId,
                            it.inFlightCompanyId,
                            it.retryRequired,
                            it.continuationRequired,
                        )
                    },
                )
                .also { require(validCollaborationRecoveryProgress(it)) }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(value: CollaborationRecoveryProgress): String {
        recoveryScope(access)
        require(validCollaborationRecoveryProgress(value))
        val dto =
            ProgressDto(
                2,
                value.sweep?.let {
                    SweepDto(
                        it.wakeGeneration,
                        it.afterCompanyId,
                        it.inFlightCompanyId,
                        it.retryRequired,
                        it.continuationRequired,
                    )
                },
            )
        val bytes = Json.encodeToString(dto).encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size in 1..1024)
            Base64.encode(cipher.encrypt(key, aad(value.generation), bytes)).also {
                require(it.length <= 2048)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-company-recovery-v1",
                    access.audience,
                    access.accountId,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}

@Serializable private data class ProgressDto(val schema: Int, val sweep: SweepDto?)

@Serializable
private data class SweepDto(
    val wakeGeneration: String,
    val afterCompanyId: String?,
    val inFlightCompanyId: String?,
    val retryRequired: Boolean,
    val continuationRequired: Boolean = false,
)
