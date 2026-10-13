package dev.fajar.starter.leave.data.encoding

import dev.fajar.starter.database.ReplicaDocument
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.LEAVE_FRESHNESS_PERIOD
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.sync.domain.entities.SyncCollection
import kotlinx.serialization.json.Json

/** Maps one admitted reference to authenticated documents; no storage or acquisition policy. */
suspend fun encodeLeaveReplicaEntry(
    entry: LeaveReplicaEntry,
    checkpoint: LeaveCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): List<ReplicaDocument> {
    val reference = entry.reference
    val id = leaveReplicaId(reference.collection, reference.id)
    return when (val record = entry.record) {
        null -> {
            val removed = mutableListOf(ReplicaDocument(id, reference.version, "deleted/$id", null))
            if (reference.collection == SyncCollection.LEAVE_REQUESTS) {
                val headerId = leaveDetailId(reference.id, "header")
                removed += ReplicaDocument(headerId, reference.version, headerId, null)
            }
            removed.toList()
        }
        is LeaveReplicaRecord.Request -> {
            require(reference.collection == SyncCollection.LEAVE_REQUESTS)
            require(reference.id == record.value.id && reference.version == record.value.version)
            val acquiredAt = requireNotNull(entry.acquiredAt)
            val snapshot =
                LeaveRequestSnapshot(
                    checkpoint.access,
                    Json.encodeToString(
                        listOf(checkpoint.generation, checkpoint.revision.toString(), reference.id)
                    ),
                    LeaveRequestSelection(reference.id),
                    record.value,
                    acquiredAt,
                    acquiredAt + LEAVE_FRESHNESS_PERIOD,
                )
            encodeLeaveRequestRecords(
                snapshot,
                checkpoint,
                cipher,
                key,
                record.value.history.nextCursor?.toLong(),
            )
        }
        is LeaveReplicaRecord.Balance -> {
            require(reference.collection == SyncCollection.LEAVE_BALANCES)
            require(
                reference.id == record.value.accountId && reference.version == record.value.version
            )
            listOf(
                encodeLeaveDocument(
                    cipher,
                    key,
                    checkpoint,
                    id,
                    reference.version,
                    record.value.replicaSortKey(),
                    leaveRecordTags(key, checkpoint.access, record),
                    Json.encodeToString(record.toDto()),
                )
            )
        }
    }
}
