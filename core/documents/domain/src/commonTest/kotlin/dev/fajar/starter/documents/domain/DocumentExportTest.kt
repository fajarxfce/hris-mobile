@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_DOWNLOAD_CHUNK_BYTES
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class DocumentExportTest {
    @Test
    fun completeCopyUsesBoundedBuffersAndRevalidatesBeforeDisclosureAndCommit() = runTest {
        val f = DocumentExportFixture()
        assertEquals(AppResult.Success(true), f.export())
        assertEquals(
            listOf(0L, DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(), DOCUMENT_DOWNLOAD_CHUNK_BYTES * 2L),
            f.reads,
        )
        assertEquals(f.reads, f.writes)
        assertEquals(2, f.download.inspections)
        assertEquals(1, f.completed)
        assertEquals(f.selections, f.releases)
        assertTrue(f.buffers.all { it.all { byte -> byte == 0.toByte() } })
        assertEquals(AppResult.Success(true), f.export())
        assertEquals(
            2,
            f.selections.distinct().size,
            "Every selection owns a fresh output identity",
        )
    }

    @Test
    fun dismissedPickerDoesNotAcquireOrReadProtectedContent() = runTest {
        val f = DocumentExportFixture()
        f.select = { AppResult.Success(false) }
        assertEquals(AppResult.Success(false), f.export())
        assertEquals(0, f.download.inspections)
        assertTrue(f.reads.isEmpty())
        assertEquals(f.selections, f.releases)
    }

    @Test
    fun downloadedProofAndOriginMustStillDescribeTheStoredFile() = runTest {
        val f = DocumentExportFixture()
        for (invalid in
            listOf(
                f.file.copy(sha256 = null),
                f.file.copy(sha256 = "b".repeat(64)),
                f.file.copy(storedBytes = 1),
                f.file.copy(draft = f.file.draft.copy(download = null)),
                f.file.copy(
                    draft =
                        f.file.draft.copy(
                            declaration = f.file.draft.declaration.copy(name = "other.pdf")
                        )
                ),
            )) {
            f.stored = invalid
            assertIs<AppResult.Failed>(f.export())
        }
        assertTrue(f.selections.isEmpty())
        assertTrue(f.reads.isEmpty())
    }

    @Test
    fun aChangedAccountWhileChoosingADestinationPreventsAllPlaintextReads() = runTest {
        val f = DocumentExportFixture()
        f.select = {
            f.base.sessions.value = AppResult.Success(null)
            AppResult.Success(true)
        }
        assertEquals("client.session_changed", assertIs<AppResult.Failed>(f.export()).failure.code)
        assertTrue(f.reads.isEmpty())
        assertEquals(0, f.download.inspections)
        assertEquals(f.selections, f.releases)
    }

    @Test
    fun aCachedCopyDoesNotBypassFreshContentDenial() = runTest {
        val f = DocumentExportFixture()
        val denied =
            AppResult.Failed(Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"))
        f.download.inspect = { denied }
        assertEquals(denied, f.export())
        assertTrue(f.reads.isEmpty())
        assertEquals(0, f.completed)
        assertEquals(f.file, f.stored, "Ciphertext is retained independently of permission")
        assertEquals(f.selections, f.releases)
    }

    @Test
    fun lateCancelledSelectionIsReleasedWithoutReadingTheCache() = runTest {
        val f = DocumentExportFixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.select = {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(true)
        }
        val request = async { f.export() }
        started.await()
        request.cancel()
        release.complete(Unit)
        request.join()
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(f.selections, f.releases)
        assertTrue(f.reads.isEmpty())
    }

    @Test
    fun cancellationAfterCacheAcquisitionClearsTheLateBufferBeforeOutput() = runTest {
        val f = DocumentExportFixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val read = f.read
        f.read = { offset ->
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            read(offset)
        }
        val request = async { f.export() }
        started.await()
        request.cancel()
        release.complete(Unit)
        request.join()
        assertFailsWith<CancellationException> { request.await() }
        assertTrue(f.writes.isEmpty())
        assertTrue(f.buffers.single().all { it == 0.toByte() })
        assertEquals(f.selections, f.releases)
    }

    @Test
    fun pendingOutputCancellationStopsFurtherReadsAndClearsItsBorrowedBuffer() = runTest {
        val f = DocumentExportFixture()
        val started = CompletableDeferred<Unit>()
        var stopped = false
        f.write = {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val request = async { f.export() }
        started.await()
        request.cancelAndJoin()
        assertTrue(stopped)
        assertEquals(1, f.reads.size)
        assertEquals(0, f.completed)
        assertTrue(f.buffers.single().all { it == 0.toByte() })
        assertEquals(f.selections, f.releases)
    }

    @Test
    fun revokedAccessAfterAWritePreventsTheNextReadAndCompletion() = runTest {
        val f = DocumentExportFixture()
        f.write = {
            f.revoke()
            AppResult.Success(Unit)
        }
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.export()).failure.code)
        assertEquals(1, f.reads.size)
        assertEquals(0, f.completed)
        assertTrue(f.buffers.single().all { it == 0.toByte() })
        assertEquals(f.selections, f.releases)
    }

    @Test
    fun finalResourceDenialPreventsCommitAndKeepsItsClassification() = runTest {
        val f = DocumentExportFixture()
        val denied =
            AppResult.Failed(
                Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied")
            )
        f.download.inspect = { if (f.writes.isEmpty()) AppResult.Success(Unit) else denied }
        assertEquals(denied, f.export())
        assertEquals(3, f.writes.size)
        assertEquals(0, f.completed)
        assertEquals(f.selections, f.releases)
    }

    @Test
    fun invalidExtentFailsOnceInsteadOfLoopingOrCommitting() = runTest {
        val f = DocumentExportFixture()
        f.read = { AppResult.Success(DocumentFileChunk(byteArrayOf(), "a".repeat(64))) }
        assertEquals(
            "client.document_file_changed",
            assertIs<AppResult.Failed>(f.export()).failure.code,
        )
        assertEquals(1, f.reads.size)
        assertTrue(f.writes.isEmpty())
        assertEquals(0, f.completed)
    }

    @Test
    fun fileWriteFailureIsPreservedAndDoesNotTriggerARetry() = runTest {
        val f = DocumentExportFixture()
        val failed =
            AppResult.Failed(
                Failure(FailureKind.Storage, "Unavailable", code = "client.file_write_failed")
            )
        f.write = { failed }
        assertEquals(failed, f.export())
        assertEquals(1, f.reads.size)
        assertEquals(1, f.writes.size)
        assertEquals(0, f.completed)
        assertEquals(f.selections, f.releases)
    }

    @Test
    fun pickerAndCopyHaveSeparateFiniteDeadlinesAndReleaseTheirOwners() = runTest {
        val picker = DocumentExportFixture()
        picker.select = { awaitCancellation() }
        val selection = async { picker.export() }
        runCurrent()
        advanceTimeBy(300_001)
        assertEquals(
            "client.document_selection_timeout",
            assertIs<AppResult.Failed>(selection.await()).failure.code,
        )
        assertEquals(picker.selections, picker.releases)
        val copy = DocumentExportFixture()
        copy.write = { awaitCancellation() }
        val saving = async { copy.export() }
        runCurrent()
        advanceTimeBy(120_001)
        assertEquals(
            "client.document_export_timeout",
            assertIs<AppResult.Failed>(saving.await()).failure.code,
        )
        assertEquals(copy.selections, copy.releases)
        assertEquals(0, copy.completed)
        assertTrue(copy.buffers.single().all { it == 0.toByte() })
    }
}
