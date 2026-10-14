package dev.fajar.starter.expenses.data.replica.encoding

import dev.fajar.starter.database.ReplicaDocument
import dev.fajar.starter.expenses.data.dto.ExpenseClaimPaymentsDto
import dev.fajar.starter.expenses.data.encoding.EXPENSE_CACHE_MAXIMUM_CHUNKS
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.data.replica.dto.ExpensePaymentRecordDto
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.sync.domain.entities.SyncCollection
import kotlinx.serialization.json.Json

suspend fun encodeExpenseReplicaEntry(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: ExpenseCheckpoint,
    entry: ExpenseReplicaEntry,
): List<ReplicaDocument> {
    val ref = entry.reference
    expenseId(ref.id)
    expenseVersion(ref.version)
    return when (ref.collection) {
        SyncCollection.EXPENSE_CLAIMS -> {
            val record = entry.record
            if (record == null) {
                val ids =
                    listOf("claim/${ref.id}/head", "summary/${ref.id}") +
                        (0 until EXPENSE_CACHE_MAXIMUM_CHUNKS).map {
                            "claim/${ref.id}/chunk/" + it.toString().padStart(2, '0')
                        }
                ids.map { ReplicaDocument(it, ref.version, it, null) }
            } else {
                require(
                    record is ExpenseRecord.Claim &&
                        record.snapshot.value.claim.id == ref.id &&
                        record.snapshot.value.claim.version >= ref.version
                )
                encodeExpenseClaimRecords(record.snapshot, checkpoint, cipher, key)
            }
        }
        SyncCollection.EXPENSE_PAYMENTS -> {
            val id = "payments/${ref.id}"
            val record = entry.record
            if (record == null)
                listOf(
                    encodeExpenseDocument(
                        cipher,
                        key,
                        checkpoint,
                        id,
                        ref.version,
                        id,
                        setOf(expenseClaimTag(key, checkpoint.access, ref.id)),
                        Json.encodeToString(ExpensePaymentRecordDto(null)),
                    )
                )
            else {
                require(
                    record is ExpenseRecord.Payments &&
                        record.progress.claimId == ref.id &&
                        record.progress.version >= ref.version
                )
                val progress = record.progress
                val dto =
                    ExpenseClaimPaymentsDto(
                        progress.claimId,
                        progress.version,
                        progress.attempts.map { it.toDto() },
                    )
                dto.toClaimPayments(ref.id)
                listOf(
                    encodeExpenseDocument(
                        cipher,
                        key,
                        checkpoint,
                        id,
                        progress.version,
                        id,
                        setOf(expenseClaimTag(key, checkpoint.access, ref.id)),
                        Json.encodeToString(ExpensePaymentRecordDto(dto)),
                    )
                )
            }
        }
        else -> error("Unsupported expense collection.")
    }
}
