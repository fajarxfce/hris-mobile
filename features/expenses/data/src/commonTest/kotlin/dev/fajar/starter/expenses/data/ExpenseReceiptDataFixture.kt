package dev.fajar.starter.expenses.data

import dev.fajar.starter.expenses.data.datasources.ExpenseReceiptDataSource
import dev.fajar.starter.expenses.data.dto.ExpenseReceiptHeadersDto
import dev.fajar.starter.expenses.domain.entities.ExpenseReceipt
import dev.fajar.starter.expenses.domain.policies.EXPENSE_RECEIPT_CHUNK_BYTES
import dev.fajar.starter.fileio.Sha256Digest

class ExpenseReceiptDataFixture : ExpenseReceiptDataSource {
    val access = ExpenseDataFixture().access
    val submissionId = "88888888-8888-4888-8888-888888888888"
    val body = ByteArray(EXPENSE_RECEIPT_CHUNK_BYTES + 17) { (it % 251 + 1).toByte() }
    val receipt =
        ExpenseReceipt(
            "99999999-9999-4999-8999-999999999999",
            "receipt.pdf",
            "application/pdf",
            body.size.toLong(),
            Sha256Digest().apply { update(body) }.finishHex(),
            false,
        )
    val tag = "\"${receipt.id}-${receipt.sha256}\""
    var headers =
        ExpenseReceiptHeadersDto(
            listOf(tag),
            listOf("1"),
            listOf(receipt.mediaType),
            emptyList(),
            listOf("bytes 0-0/${receipt.size}"),
        )
    val ranges = mutableListOf<Long>()
    val acquired = mutableListOf<ByteArray>()
    var chunkCall: suspend (Long, Int) -> ByteArray = { offset, maximum ->
        body.copyOfRange(offset.toInt(), minOf(body.size, offset.toInt() + maximum))
    }

    override suspend fun inspect(
        sessionId: String,
        companyId: String,
        submissionId: String,
        revisionId: String,
    ): ExpenseReceiptHeadersDto {
        check(
            sessionId == access.sessionId &&
                companyId == access.companyId &&
                submissionId == this.submissionId &&
                revisionId == receipt.id
        )
        return headers
    }

    override suspend fun chunk(
        sessionId: String,
        companyId: String,
        submissionId: String,
        revisionId: String,
        entityTag: String,
        size: Long,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray {
        check(
            sessionId == access.sessionId &&
                companyId == access.companyId &&
                submissionId == this.submissionId &&
                revisionId == receipt.id
        )
        check(
            entityTag == tag && size == receipt.size && maximumBytes == EXPENSE_RECEIPT_CHUNK_BYTES
        )
        ranges += offset
        return chunkCall(offset, maximumBytes).also { acquired += it }
    }
}
