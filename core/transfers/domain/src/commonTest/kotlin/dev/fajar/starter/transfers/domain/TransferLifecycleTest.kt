@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.transfers.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.security.domain.access.entities.Permission
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.transfers.domain.entities.*
import dev.fajar.starter.transfers.domain.repositories.TransferInputRepository
import dev.fajar.starter.transfers.domain.usecases.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*

class TransferLifecycleTest {
    @Test
    fun cancellationAfterDraftCreationStillCleansTheKnownIdentity() = runTest {
        val gate = CompletableDeferred<Unit>()
        val queue =
            object : TestQueue() {
                override suspend fun create(
                    sessionId: String,
                    transfer: Transfer,
                    maxBytes: Long,
                    maxItems: Int,
                ): AppResult<Boolean> {
                    val result = super.create(sessionId, transfer, maxBytes, maxItems)
                    withContext(NonCancellable) { gate.await() }
                    currentCoroutineContext().ensureActive()
                    return result
                }
            }
        val input =
            object : TransferInputRepository {
                override suspend fun metadata(sourceId: String) = AppResult.Success(testFile)

                override fun read(sourceId: String) = error("cancelled before import")
            }
        val job = launch {
            EnqueueUpload(TestSessions(), TestAccess(), input, queue, TestScheduler())("source")
        }
        runCurrent()
        assertEquals(1, queue.rows.value.size)
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertTrue(queue.rows.value.isEmpty())
    }

    @Test
    fun deniedUploadNeverReadsTheSource() = runTest {
        val sessions = TestSessions()
        val queue = TestQueue()
        val access = TestAccess().apply { permissions = setOf(Permission.DownloadFile) }
        val input =
            object : TransferInputRepository {
                override suspend fun metadata(sourceId: String) = error("must not read")

                override fun read(sourceId: String) = error("must not read")
            }
        val result = EnqueueUpload(sessions, access, input, queue, TestScheduler())("source")
        assertEquals(FailureKind.AccessDenied, (result as AppResult.Failed).failure.kind)
        assertTrue(queue.rows.value.isEmpty())
    }

    @Test
    fun cancelledImportClosesInputAndRemovesItsPartialDraft() = runTest {
        val sessions = TestSessions()
        val queue = TestQueue()
        var closed = false
        val input =
            object : TransferInputRepository {
                override suspend fun metadata(sourceId: String) = AppResult.Success(testFile)

                override fun read(sourceId: String) = flow {
                    try {
                        emit(AppResult.Success(byteArrayOf(1, 2, 3, 4)))
                        awaitCancellation()
                    } finally {
                        closed = true
                    }
                }
            }
        val job = launch {
            EnqueueUpload(sessions, TestAccess(), input, queue, TestScheduler())("source")
        }
        runCurrent()
        assertEquals(4, queue.rows.value.single().storedBytes)
        job.cancelAndJoin()
        assertTrue(closed)
        assertTrue(queue.rows.value.isEmpty())
        assertTrue(queue.chunks.isEmpty())
    }

    @Test
    fun failedInputPreservesClassificationAndRemovesDraft() = runTest {
        val queue = TestQueue()
        val failure = Failure(FailureKind.Permission, "Access revoked.")
        val input =
            object : TransferInputRepository {
                override suspend fun metadata(sourceId: String) = AppResult.Success(testFile)

                override fun read(sourceId: String) = flow {
                    emit(AppResult.Success(byteArrayOf(1, 2)))
                    emit(AppResult.Failed(failure))
                }
            }
        assertEquals(
            AppResult.Failed(failure),
            EnqueueUpload(TestSessions(), TestAccess(), input, queue, TestScheduler())("source"),
        )
        assertTrue(queue.rows.value.isEmpty())
    }

    @Test
    fun pauseCancelsPendingIoAndRejectsLateCompletion() = runTest {
        val queue = TestQueue().apply { rows.value = listOf(testTransfer()) }
        val sessions = TestSessions()
        val gate = CompletableDeferred<Unit>()
        var exited = false
        val gateway =
            TestGateway().apply {
                onDownload = {
                    try {
                        withContext(NonCancellable) { gate.await() }
                        AppResult.Success(ByteArray(8))
                    } finally {
                        exited = true
                    }
                }
            }
        val job = async { SyncTransfers(sessions, TestAccess(), queue, gateway)() }
        runCurrent()
        ChangeTransfer(sessions, queue, TestScheduler())("transfer", TransferAction.Pause)
        runCurrent()
        gate.complete(Unit)
        job.await()
        assertTrue(exited)
        assertEquals(TransferStatus.Paused, queue.rows.value.single().status)
        assertEquals(0, queue.rows.value.single().offset)
        assertTrue(queue.chunks.isEmpty())
        assertEquals(0, queue.collectors)
        assertEquals(0, sessions.collectors)
    }

    @Test
    fun sessionChangeCancelsActiveDownloadAndReleasesObservers() = runTest {
        val queue = TestQueue().apply { rows.value = listOf(testTransfer()) }
        val sessions = TestSessions()
        var cancelled = false
        val gateway =
            TestGateway().apply {
                onDownload = {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        val job = async { SyncTransfers(sessions, TestAccess(), queue, gateway)() }
        runCurrent()
        queue.owner = "two"
        queue.rows.value = emptyList()
        sessions.value.value = null
        runCurrent()
        job.await()
        assertTrue(cancelled)
        assertTrue(queue.chunks.isEmpty())
        assertEquals(0, queue.collectors)
        assertEquals(0, sessions.collectors)
    }

    @Test
    fun uploadReconcilesServerOffsetAfterLostAcknowledgement() = runTest {
        val queue =
            TestQueue().apply {
                rows.value = listOf(testTransfer(TransferDirection.Upload).copy(storedBytes = 8))
                chunks["transfer"] =
                    mutableMapOf(0L to byteArrayOf(1, 2, 3, 4), 4L to byteArrayOf(5, 6, 7, 8))
            }
        val gateway = TestGateway()
        gateway.onUpload = { transfer, bytes ->
            gateway.serverOffset = transfer.offset + 2
            AppResult.Failed(Failure(FailureKind.Network, "Connection interrupted."))
        }
        val sync = SyncTransfers(TestSessions(), TestAccess(), queue, gateway)
        assertIs<SyncResult.Retry>(sync())
        assertEquals(0, queue.rows.value.single().offset)
        val sent = mutableListOf<List<Byte>>()
        gateway.onUpload = { transfer, bytes ->
            sent += bytes.toList()
            gateway.serverOffset = transfer.offset + bytes.size
            AppResult.Success(gateway.serverOffset)
        }
        sync()
        assertEquals(listOf(0L, 2L, 4L), gateway.sentOffsets)
        assertEquals(listOf(listOf<Byte>(3, 4), listOf<Byte>(5, 6, 7, 8)), sent)
        assertEquals(TransferStatus.Completed, queue.rows.value.single().status)
        assertTrue(queue.chunks.isEmpty())
    }

    @Test
    fun parentCancellationRetainsCheckpointForNextWorker() = runTest {
        val queue = TestQueue().apply { rows.value = listOf(testTransfer()) }
        val sessions = TestSessions()
        val gateway = TestGateway().apply { onDownload = { awaitCancellation() } }
        val sync = SyncTransfers(sessions, TestAccess(), queue, gateway)
        val job = launch { sync() }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(TransferStatus.Running, queue.rows.value.single().status)
        assertEquals(0, queue.rows.value.single().offset)
        assertEquals(0, queue.collectors)
        gateway.onDownload = { AppResult.Success(ByteArray(8)) }
        sync()
        assertEquals(TransferStatus.Completed, queue.rows.value.single().status)
    }
}
