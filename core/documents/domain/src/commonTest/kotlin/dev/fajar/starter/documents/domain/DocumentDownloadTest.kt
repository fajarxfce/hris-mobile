@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_DOWNLOAD_CHUNK_BYTES
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class DocumentDownloadTest {
    @Test
    fun explicitSelectionReservesOnceAndReplacementGetsANewIdentity() = runTest {
        val f = DocumentDownloadFixture()
        val first = async { f.prepare() }
        val second = async { f.prepare() }
        val saved = assertIs<AppResult.Success<LocalDocumentFile>>(first.await()).value
        assertEquals(AppResult.Success(saved), second.await())
        assertEquals(1, f.reservations)
        assertEquals(f.evidence, saved.draft.download?.evidence)
        assertNotEquals(f.evidence.revisionId, saved.draft.id)
        assertTrue(f.offsets.isEmpty())
        assertEquals(
            AppResult.Success(true),
            f.files.remove(f.access, saved.draft.id, saved.revision),
        )
        val replacement = assertIs<AppResult.Success<LocalDocumentFile>>(f.prepare()).value
        assertNotEquals(saved.draft.id, replacement.draft.id)
        assertEquals(
            "client.document_file_changed",
            assertIs<AppResult.Failed>(f.download(saved)).failure.code,
        )
        assertTrue(f.offsets.isEmpty())
    }

    @Test
    fun staleOrForeignSelectionsCannotReserveOrReadContent() = runTest {
        val f = DocumentDownloadFixture()
        val invalid =
            listOf(
                f.catalog.copy(expiresAt = f.base.now),
                f.catalog.copy(fetchedAt = f.base.now + 1.seconds),
                f.catalog.copy(access = f.access.copy(companyId = f.evidence.documentId)),
                f.catalog.copy(page = DocumentEvidencePage(emptyList())),
            )
        for (catalog in invalid) assertIs<AppResult.Failed>(
            f.prepare(f.base.workspace.id, catalog, f.evidence.revisionId)
        )
        assertEquals(0, f.inspections)
        assertEquals(0, f.reservations)
        f.base.workspaces.value = AppResult.Success(f.base.workspace.copy(permissions = emptySet()))
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.prepare()).failure.code)
        assertEquals(0, f.inspections)
    }

    @Test
    fun changedCompanyDuringPreparationCannotPublishAReservation() = runTest {
        val f = DocumentDownloadFixture()
        f.inspect = {
            f.base.workspaces.value =
                AppResult.Success(
                    f.base.workspace.copy(
                        company = f.base.workspace.company.copy(id = f.evidence.documentId)
                    )
                )
            AppResult.Success(Unit)
        }
        assertEquals(
            "client.document_scope_changed",
            assertIs<AppResult.Failed>(f.prepare()).failure.code,
        )
        assertEquals(0, f.reservations)
    }

    @Test
    fun transferRevalidatesThenPublishesOnlyVerifiedCompleteContent() = runTest {
        val f = DocumentDownloadFixture()
        val file = assertIs<AppResult.Success<LocalDocumentFile>>(f.prepare()).value
        val ready = assertIs<AppResult.Success<LocalDocumentFile>>(f.download(file)).value
        assertEquals(
            listOf(0L, DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(), DOCUMENT_DOWNLOAD_CHUNK_BYTES * 2L),
            f.offsets,
        )
        assertEquals(f.evidence.sha256, ready.sha256)
        assertEquals(f.evidence.size, ready.storedBytes)
        assertEquals(1, f.completions)
        assertEquals(2, f.inspections)
        assertTrue(f.buffers.all { buffer -> buffer.all { it == 0.toByte() } })
        assertEquals(AppResult.Success(ready), f.download(ready))
        assertEquals(3, f.inspections, "Existing content still needs current content admission")
        assertEquals(3, f.offsets.size)
    }

    @Test
    fun interruptionRetainsProgressAndResumeStartsAtCommittedOffset() = runTest {
        val f = DocumentDownloadFixture()
        val file = assertIs<AppResult.Success<LocalDocumentFile>>(f.prepare()).value
        val read = f.read
        val disconnected =
            AppResult.Failed(Failure(FailureKind.Network, "Offline", code = "client.network"))
        f.read = { offset -> if (offset == 0L) read(offset) else disconnected }
        assertEquals(disconnected, f.download(file))
        val partial = f.rows.value.single()
        assertNull(partial.sha256)
        assertEquals(DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(), partial.storedBytes)
        assertEquals(0, f.completions)
        assertEquals(listOf(0L, DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong()), f.offsets)
        f.read = read
        val ready = assertIs<AppResult.Success<LocalDocumentFile>>(f.download(partial)).value
        assertEquals(f.evidence.sha256, ready.sha256)
        assertEquals(
            listOf(
                0L,
                DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(),
                DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(),
                DOCUMENT_DOWNLOAD_CHUNK_BYTES * 2L,
            ),
            f.offsets,
        )
        assertTrue(f.buffers.all { buffer -> buffer.all { it == 0.toByte() } })
    }

    @Test
    fun lateCancelledNetworkContentNeverReachesStorage() = runTest {
        val f = DocumentDownloadFixture()
        val file = assertIs<AppResult.Success<LocalDocumentFile>>(f.prepare()).value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val read = f.read
        f.read = { offset ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            read(offset)
        }
        val request = async { f.download(file) }
        entered.await()
        request.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }
        request.join()
        assertEquals(0, f.writes)
        assertEquals(0, f.completions)
        assertTrue(f.buffers.single().all { it == 0.toByte() })
    }

    @Test
    fun cancellationAfterStorageCommitKeepsTheCheckpointButStopsFurtherReads() = runTest {
        val f = DocumentDownloadFixture()
        val file = assertIs<AppResult.Success<LocalDocumentFile>>(f.prepare()).value
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterAppend = {
            committed.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val request = async { f.download(file) }
        committed.await()
        request.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }
        request.join()
        assertEquals(DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(), f.rows.value.single().storedBytes)
        assertEquals(1, f.offsets.size)
        assertEquals(0, f.completions)
        assertTrue(f.buffers.single().all { it == 0.toByte() })
    }

    @Test
    fun revokedAccessAfterPendingReadDiscardsTheBuffer() = runTest {
        val f = DocumentDownloadFixture()
        val file = assertIs<AppResult.Success<LocalDocumentFile>>(f.prepare()).value
        val read = f.read
        f.read = { offset ->
            f.base.workspaces.value =
                AppResult.Success(f.base.workspace.copy(permissions = emptySet()))
            read(offset)
        }
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.download(file)).failure.code)
        assertEquals(0, f.writes)
        assertEquals(0, f.completions)
        assertTrue(f.buffers.single().all { it == 0.toByte() })
    }

    @Test
    fun cachedCompletionCannotBypassAContentDenial() = runTest {
        val f = DocumentDownloadFixture()
        val file = assertIs<AppResult.Success<LocalDocumentFile>>(f.prepare()).value
        val ready = assertIs<AppResult.Success<LocalDocumentFile>>(f.download(file)).value
        val denied =
            AppResult.Failed(
                Failure(FailureKind.AccessDenied, "Access denied", code = "mfa_required")
            )
        f.inspect = { denied }
        assertEquals(denied, f.download(ready))
        assertEquals(3, f.offsets.size)
        assertEquals(
            ready,
            f.rows.value.single(),
            "Retained ciphertext does not grant content access",
        )
    }

    @Test
    fun malformedProgressOrChecksumCannotLoopOrPublishCompletion() = runTest {
        val stalled = DocumentDownloadFixture()
        val first = assertIs<AppResult.Success<LocalDocumentFile>>(stalled.prepare()).value
        stalled.append = { file, _ -> file }
        assertEquals(
            "client.document_file_changed",
            assertIs<AppResult.Failed>(stalled.download(first)).failure.code,
        )
        assertEquals(1, stalled.offsets.size)
        assertEquals(0, stalled.completions)
        val corrupt = DocumentDownloadFixture()
        val file = assertIs<AppResult.Success<LocalDocumentFile>>(corrupt.prepare()).value
        corrupt.complete = { DocumentFileCompletion.ChecksumMismatch }
        assertEquals(
            "client.document_checksum_mismatch",
            assertIs<AppResult.Failed>(corrupt.download(file)).failure.code,
        )
        assertNull(corrupt.rows.value.single().sha256)
    }

    @Test
    fun aPendingTransferHasAFiniteDeadlineAndNoAutomaticRetry() = runTest {
        val f = DocumentDownloadFixture()
        val file = assertIs<AppResult.Success<LocalDocumentFile>>(f.prepare()).value
        var closed = false
        f.read = {
            try {
                awaitCancellation()
            } finally {
                closed = true
            }
        }
        val request = async { f.download(file) }
        runCurrent()
        advanceTimeBy(120_001)
        assertEquals(
            "client.document_timeout",
            assertIs<AppResult.Failed>(request.await()).failure.code,
        )
        assertTrue(closed)
        assertEquals(1, f.offsets.size)
        assertEquals(0, f.writes)
    }
}
