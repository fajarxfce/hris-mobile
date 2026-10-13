package dev.fajar.starter.expenses.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_MAXIMUM_BYTES
import dev.fajar.starter.expenses.data.dto.ExpenseReceiptDto
import dev.fajar.starter.expenses.data.mappers.toReceipt
import dev.fajar.starter.expenses.data.repositories.RemoteExpenseReceiptRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.EXPENSE_RECEIPT_CHUNK_BYTES
import dev.fajar.starter.network.RequestFailureException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class ExpenseReceiptDataTest {
    @Test
    fun exactBoundedRangesAreEmittedSequentiallyAndTheirBuffersAreCleared() = runTest {
        val f = ExpenseReceiptDataFixture()
        val repo = RemoteExpenseReceiptRepository(f)
        assertEquals(AppResult.Success(Unit), repo.inspect(f.access, f.submissionId, f.receipt))
        val copied = mutableListOf<ByteArray>()
        repo.chunks(f.access, f.submissionId, f.receipt).collect { result ->
            val chunk = assertIs<AppResult.Success<ExpenseReceiptChunk>>(result).value
            assertEquals(copied.sumOf { it.size }.toLong(), chunk.offset)
            copied += chunk.bytes.copyOf()
        }
        assertEquals(listOf(0L, EXPENSE_RECEIPT_CHUNK_BYTES.toLong()), f.ranges)
        assertContentEquals(f.body, copied[0] + copied[1])
        assertTrue(f.acquired.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun streamingDoesNotReadAheadAndCancellationClearsTheEmittedBuffer() = runTest {
        val f = ExpenseReceiptDataFixture()
        val entered = CompletableDeferred<Unit>()
        val task = launch {
            RemoteExpenseReceiptRepository(f).chunks(f.access, f.submissionId, f.receipt).collect {
                assertIs<AppResult.Success<ExpenseReceiptChunk>>(it)
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        assertEquals(listOf(0L), f.ranges)
        task.cancelAndJoin()
        assertTrue(f.acquired.single().all { it == 0.toByte() })
        assertEquals(1, f.ranges.size)
    }

    @Test
    fun changedDigestsAndInvalidExtentsCannotCompleteTheStream() = runTest {
        for (corruptHash in listOf(false, true)) {
            val f = ExpenseReceiptDataFixture()
            f.chunkCall = { offset, maximum ->
                f.body
                    .copyOfRange(offset.toInt(), minOf(f.body.size, offset.toInt() + maximum))
                    .let {
                        if (corruptHash) it.also { bytes -> bytes[0] = 0 }
                        else it.copyOf(it.size - 1)
                    }
            }
            val outcomes = mutableListOf<AppResult<ExpenseReceiptChunk>>()
            RemoteExpenseReceiptRepository(f).chunks(f.access, f.submissionId, f.receipt).collect {
                outcomes += it
            }
            assertIs<AppResult.Failed>(outcomes.last())
            assertEquals(if (corruptHash) 2 else 1, f.ranges.size)
            assertTrue(f.acquired.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    @Test
    fun ambiguousOrChangedHeadersCannotAuthorizeAnExport() = runTest {
        val f = ExpenseReceiptDataFixture()
        val valid = f.headers
        for (headers in
            listOf(
                valid.copy(ranges = emptyList()),
                valid.copy(ranges = listOf("bytes 0-0/1")),
                valid.copy(ranges = valid.ranges + valid.ranges),
                valid.copy(lengths = listOf("0")),
                valid.copy(lengths = valid.lengths + valid.lengths),
                valid.copy(entityTags = listOf("W/${f.tag}")),
                valid.copy(entityTags = valid.entityTags + valid.entityTags),
                valid.copy(mediaTypes = listOf("text/html")),
                valid.copy(encodings = listOf("gzip")),
            )) {
            f.headers = headers
            assertIs<AppResult.Failed>(
                RemoteExpenseReceiptRepository(f).inspect(f.access, f.submissionId, f.receipt)
            )
        }
    }

    @Test
    fun cancelledLateAcquisitionErasesItsBytesInsteadOfPublishingThem() = runTest {
        val f = ExpenseReceiptDataFixture()
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        f.chunkCall = { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { released.await() }
            f.body.copyOf(EXPENSE_RECEIPT_CHUNK_BYTES)
        }
        val task = async {
            RemoteExpenseReceiptRepository(f).chunks(f.access, f.submissionId, f.receipt).collect()
        }
        entered.await()
        task.cancel()
        released.complete(Unit)
        assertFailsWith<CancellationException> { task.await() }
        assertTrue(f.acquired.single().all { it == 0.toByte() })
        assertEquals(1, f.ranges.size)
    }

    @Test
    fun technicalFailuresKeepTheirDomainClassificationWithoutRetries() = runTest {
        val f = ExpenseReceiptDataFixture()
        val denied = Failure(FailureKind.AccessDenied, "Access denied", code = "mfa_required")
        f.chunkCall = { _, _ -> throw RequestFailureException(denied) }
        val results =
            RemoteExpenseReceiptRepository(f).chunks(f.access, f.submissionId, f.receipt).toList()
        assertEquals(listOf(AppResult.Failed(denied)), results)
        assertEquals(1, f.ranges.size)
    }

    @Test
    fun submittedMetadataAcceptsTheServerLimitAndRejectsTraversalAndLargerContent() {
        val f = ExpenseReceiptDataFixture()
        val dto =
            ExpenseReceiptDto(
                f.receipt.id,
                f.receipt.fileName,
                f.receipt.mediaType,
                DOCUMENT_MAXIMUM_BYTES,
                f.receipt.sha256,
                false,
            )
        assertEquals(DOCUMENT_MAXIMUM_BYTES, dto.toReceipt().size)
        assertFails { dto.copy(size = DOCUMENT_MAXIMUM_BYTES + 1).toReceipt() }
        assertFails { dto.copy(fileName = "../receipt.pdf").toReceipt() }
    }
}
