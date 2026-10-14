package dev.fajar.starter.expenses.data.replica.encoding

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.ReplicaQueryPage
import dev.fajar.starter.expenses.data.mappers.toClaimPayments
import dev.fajar.starter.expenses.data.replica.dto.ExpensePaymentRecordDto
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.validateExpenseView
import dev.fajar.starter.expenses.domain.replica.entities.ExpenseReplicaDetails
import dev.fajar.starter.securestorage.ContentCipher
import kotlinx.serialization.json.Json

/** Decodes one atomic local query without changing the claim's action-review timestamps. */
internal suspend fun decodeExpenseReplicaDetails(
    page: ReplicaQueryPage,
    access: ExpenseAccess,
    id: String,
    historyAfter: Long?,
    cipher: ContentCipher,
    key: ByteArray,
): ExpenseReplicaDetails {
    val raw = page.checkpoint ?: return ExpenseReplicaDetails(null, null)
    val checkpoint = decodeExpenseCheckpoint(cipher, key, access, raw)
    require(page.pages.size == if (historyAfter == null) 1 else 2)
    val documents = page.pages.first()
    val head = documents.singleOrNull { it.id == "claim/$id/head" }
    if (head == null) return ExpenseReplicaDetails(checkpoint, null)
    val payment = requireNotNull(documents.singleOrNull { it.id == "payments/$id" })
    val snapshot =
        decodeExpenseClaimRecords(
            documents.filter { it != payment },
            access,
            raw.handle.generation,
            id,
            cipher,
            key,
        )
    require(
        payment.sortKey == payment.id && payment.tags == setOf(expenseClaimTag(key, access, id))
    )
    val paymentDto =
        Json.decodeFromString<ExpensePaymentRecordDto>(
            decodeExpenseDocument(cipher, key, access, raw.handle.generation, payment)
        )
    val progress =
        paymentDto.progress?.toClaimPayments(id)?.also { require(it.version == payment.version) }
            ?: ExpenseClaimPayments(id, 0, emptyList())
    val currentView =
        ExpenseView(
            snapshot.value.claim,
            snapshot.value.submission,
            snapshot.value.history,
            null,
            progress.attempts,
        )
    require(validateExpenseView(currentView) is AppResult.Success)
    val current =
        ExpenseSnapshot(
            snapshot.access,
            snapshot.generation,
            currentView,
            snapshot.fetchedAt,
            snapshot.refreshAfter,
        )
    val history =
        if (historyAfter == null) snapshot.value.history
        else
            page.pages[1].singleOrNull()?.let {
                decodeExpenseHistoryPage(
                        it,
                        access,
                        raw.handle.generation,
                        id,
                        historyAfter,
                        cipher,
                        key,
                    )
                    .also { value -> require(value.claimVersion <= snapshot.value.claim.version) }
                    .history
            }
    val selected =
        history?.let {
            current
                .copy(value = currentView.copy(history = it, historyAfter = historyAfter))
                .also { value -> require(validateExpenseView(value.value) is AppResult.Success) }
        }
    return ExpenseReplicaDetails(checkpoint, selected, current)
}
