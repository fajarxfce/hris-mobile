@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.data.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.data.datasources.ExpenseReceiptDataSource
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.ExpenseReceiptRepository
import dev.fajar.starter.fileio.Sha256Digest
import dev.fajar.starter.network.safeApiCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.koin.core.annotation.Single

@Single
class RemoteExpenseReceiptRepository(private val remote: ExpenseReceiptDataSource) :
    ExpenseReceiptRepository {
    override suspend fun inspect(
        access: ExpenseAccess,
        submissionId: String,
        receipt: ExpenseReceipt,
    ) = safeApiCall {
        listOf(access.companyId, submissionId).forEach { require(Uuid.parse(it).toString() == it) }
        require(validateExpenseReceipt(receipt) is AppResult.Success)
        val headers = remote.inspect(access.sessionId, access.companyId, submissionId, receipt.id)
        require(headers.entityTags == listOf("\"${receipt.id}-${receipt.sha256}\""))
        require(headers.lengths.isEmpty() || headers.lengths == listOf("1"))
        require(headers.ranges == listOf("bytes 0-0/${receipt.size}"))
        require(headers.mediaTypes.singleOrNull()?.lowercase() == receipt.mediaType)
        require(headers.encodings.isEmpty() || headers.encodings == listOf("identity"))
    }

    override fun chunks(access: ExpenseAccess, submissionId: String, receipt: ExpenseReceipt) =
        flow {
            val valid = safeApiCall {
                listOf(access.companyId, submissionId).forEach {
                    require(Uuid.parse(it).toString() == it)
                }
                require(validateExpenseReceipt(receipt) is AppResult.Success)
            }
            if (valid is AppResult.Failed) {
                emit(valid)
                return@flow
            }
            val digest = Sha256Digest()
            val count =
                ((receipt.size + EXPENSE_RECEIPT_CHUNK_BYTES - 1) / EXPENSE_RECEIPT_CHUNK_BYTES)
                    .toInt()
            repeat(count) { index ->
                val offset = index.toLong() * EXPENSE_RECEIPT_CHUNK_BYTES
                var acquired: ByteArray? = null
                try {
                    val part = safeApiCall {
                        val bytes =
                            remote.chunk(
                                access.sessionId,
                                access.companyId,
                                submissionId,
                                receipt.id,
                                "\"${receipt.id}-${receipt.sha256}\"",
                                receipt.size,
                                offset,
                                EXPENSE_RECEIPT_CHUNK_BYTES,
                            )
                        acquired = bytes
                        currentCoroutineContext().ensureActive()
                        require(
                            bytes.size.toLong() ==
                                minOf(EXPENSE_RECEIPT_CHUNK_BYTES.toLong(), receipt.size - offset)
                        )
                        withContext(Dispatchers.Default) {
                            digest.update(bytes)
                            if (index == count - 1) require(digest.finishHex() == receipt.sha256)
                        }
                        ExpenseReceiptChunk(offset, bytes)
                    }
                    // Emission stays in the collector's context. A buffered flow could retain
                    // plaintext
                    // after cancellation; the caller must finish consuming each part before
                    // returning.
                    emit(part)
                    if (part is AppResult.Failed) return@flow
                } finally {
                    acquired?.fill(0)
                }
            }
        }
}
