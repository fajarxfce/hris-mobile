package dev.fajar.starter.expenses.data.replica.encoding

import dev.fajar.starter.database.ReplicaDocument
import dev.fajar.starter.expenses.data.replica.dto.ExpenseHistoryPageDto
import dev.fajar.starter.expenses.data.replica.mappers.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlinx.serialization.json.Json

suspend fun encodeExpenseHistoryPage(
    page: ExpenseHistoryPage,
    checkpoint: ExpenseCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaDocument {
    val dto = page.toDto()
    dto.toHistoryPage()
    val id = "history/${page.claimId}/${page.after}"
    return encodeExpenseDocument(
        cipher,
        key,
        checkpoint,
        id,
        page.claimVersion,
        id,
        setOf(expenseHistoryTag(key, checkpoint.access, page.claimId, page.after)),
        Json.encodeToString(dto),
    )
}

suspend fun decodeExpenseHistoryPage(
    document: ReplicaDocument,
    access: ExpenseAccess,
    generation: String,
    id: String,
    after: Long,
    cipher: ContentCipher,
    key: ByteArray,
): ExpenseHistoryPage {
    require(document.id == "history/$id/$after" && document.sortKey == document.id)
    require(document.tags == setOf(expenseHistoryTag(key, access, id, after)))
    val page =
        Json.decodeFromString<ExpenseHistoryPageDto>(
                decodeExpenseDocument(cipher, key, access, generation, document)
            )
            .toHistoryPage()
    require(page.claimId == id && page.after == after && page.claimVersion == document.version)
    return page
}
