package dev.fajar.starter.collaboration.data.recovery

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun readinessReceiptScope(value: CollaborationReadinessScope): ReplicaScope {
    require(validCollaborationMessageId(value.parent.nativeSessionId))
    require(
        validCollaborationMessageId(value.companyId) && validCollaborationMessageId(value.deviceId)
    )
    return recoveryScope(
        CollaborationRecoveryAccess(
            value.parent.sessionId,
            value.parent.audience,
            value.parent.accountId,
        )
    )
}

internal fun readinessReceiptPartition(value: CollaborationReadinessScope): String {
    readinessReceiptScope(value)
    return COLLABORATION_HISTORY_PREFIX +
        "readiness-control/v2/" +
        value.parent.nativeSessionId +
        "/" +
        value.companyId +
        "/" +
        value.deviceId
}

/** Only two canonical decimal revisions are retained; no packet or event-ID history. */
internal class CollaborationReadinessReceiptCodec(
    private val scope: CollaborationReadinessScope,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationReadinessReceipt {
        require(
            raw.published && raw.revision == 0L && raw.handle.scope == readinessReceiptScope(scope)
        )
        require(raw.handle.partition == readinessReceiptPartition(scope))
        require(validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..4096)
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
        return try {
            require(bytes.size in 1..2048)
            val dto =
                Json.decodeFromString<ReadinessReceiptDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            require(dto.schema == 2)
            val requested = dto.requestedRevision.toLong()
            val pending = dto.pendingRevision.toLong()
            require(
                dto.requestedRevision == requested.toString() &&
                    dto.pendingRevision == pending.toString()
            )
            CollaborationReadinessReceipt(
                    raw.handle.generation,
                    CollaborationReadinessProgress(requested, pending),
                )
                .also { require(validCollaborationReadinessProgress(it.progress)) }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(value: CollaborationReadinessReceipt): String {
        readinessReceiptScope(scope)
        require(
            validCollaborationMessageId(value.generation) &&
                validCollaborationReadinessProgress(value.progress)
        )
        val dto =
            ReadinessReceiptDto(
                2,
                value.progress.requestedRevision.toString(),
                value.progress.pendingRevision.toString(),
            )
        val bytes = Json.encodeToString(dto).encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size in 1..2048)
            Base64.encode(cipher.encrypt(key, aad(value.generation), bytes)).also {
                require(it.length <= 4096)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-readiness-v2",
                    scope.parent.audience,
                    scope.parent.accountId,
                    scope.parent.nativeSessionId,
                    scope.companyId,
                    scope.deviceId,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class ReadinessReceiptDto(
    val schema: Int,
    val requestedRevision: String,
    val pendingRevision: String,
)
