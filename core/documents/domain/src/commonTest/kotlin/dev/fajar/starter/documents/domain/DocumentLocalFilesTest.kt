package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.repositories.DocumentFileRepository
import dev.fajar.starter.documents.domain.usecases.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentLocalFilesTest {
    @Test
    fun localFilesStayObservedAfterStaleAuthorizationUntilDisposal() = runTest {
        val f = LocalFilesFixture()
        f.base.workspaces.value =
            AppResult.Success(
                f.base.workspace.copy(
                    admission = f.base.workspace.admission.copy(expiresAt = f.base.now + 2.seconds)
                )
            )
        val seen = mutableListOf<AppResult<DocumentLocalFiles>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.base.workspace.id).toList(seen)
            }
        assertEquals(
            f.file,
            assertIs<AppResult.Success<DocumentLocalFiles>>(seen.last()).value.files.single(),
        )
        assertEquals(1, f.readers)
        f.base.now += 2.seconds
        advanceTimeBy(2_001)
        runCurrent()
        assertIs<AppResult.Success<DocumentLocalFiles>>(seen.last())
        assertEquals(1, f.readers)
        f.rows.value = AppResult.Success(listOf(f.file.copy(revision = 99)))
        runCurrent()
        assertEquals(
            99,
            assertIs<AppResult.Success<DocumentLocalFiles>>(seen.last())
                .value
                .files
                .single()
                .revision,
        )
        job.cancelAndJoin()
        assertEquals(0, f.readers)
        assertEquals(0, f.base.policyReads)
    }

    @Test
    fun scopeReplacementAndRevokedPermissionsNeverReuseTheOldFiles() = runTest {
        val f = LocalFilesFixture()
        val seen = mutableListOf<AppResult<DocumentLocalFiles>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.base.workspace.id).toList(seen)
            }
        f.base.workspaces.value = AppResult.Success(f.base.workspace.copy(permissions = emptySet()))
        runCurrent()
        assertEquals("access_denied", assertIs<AppResult.Failed>(seen.last()).failure.code)
        assertEquals(0, f.readers)
        f.base.sessions.value = AppResult.Success(null)
        runCurrent()
        assertEquals("session_revoked", assertIs<AppResult.Failed>(seen.last()).failure.code)
        f.base.sessions.value = AppResult.Success(f.base.session)
        f.base.workspaces.value = AppResult.Success(f.base.workspace)
        runCurrent()
        assertIs<AppResult.Success<*>>(seen.last())
        job.cancelAndJoin()
        assertEquals(0, f.readers)
    }

    @Test
    fun removalCannotDeleteAFileChangedSinceConfirmation() = runTest {
        val f = LocalFilesFixture()
        assertEquals(
            "client.document_file_changed",
            assertIs<AppResult.Failed>(
                    f.remove(f.base.workspace.id, f.file.draft.id, f.file.revision - 1)
                )
                .failure
                .code,
        )
        assertEquals(0, f.removals)
        f.delete = { false }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    f.remove(f.base.workspace.id, f.file.draft.id, f.file.revision)
                )
                .failure
                .kind,
        )
        f.delete = { true }
        assertEquals(
            AppResult.Success(Unit),
            f.remove(f.base.workspace.id, f.file.draft.id, f.file.revision),
        )
        assertEquals(0, f.base.policyReads)
    }

    @Test
    fun cancelledOrReplacedLookupCannotProceedToDeletion() = runTest {
        val f = LocalFilesFixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.lookup = {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                AppResult.Success(f.file)
            }
        }
        val operation = async { f.remove(f.base.workspace.id, f.file.draft.id, f.file.revision) }
        started.await()
        operation.cancel()
        release.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(0, f.removals)
        f.lookup = {
            f.base.workspaces.value = AppResult.Success(null)
            AppResult.Success(f.file)
        }
        assertIs<AppResult.Failed>(f.remove(f.base.workspace.id, f.file.draft.id, f.file.revision))
        assertEquals(0, f.removals)
    }
}

private class LocalFilesFixture {
    val base = DocumentFixture()
    val file =
        LocalDocumentFile(
            DocumentFileDraft(
                "66666666-6666-6666-6666-666666666666",
                DocumentFileDeclaration("file.pdf", "application/pdf", 3),
                base.now,
            ),
            3,
            "a".repeat(64),
            2,
        )
    val rows = MutableStateFlow<AppResult<List<LocalDocumentFile>>>(AppResult.Success(listOf(file)))
    var readers = 0
    var removals = 0
    var lookup: suspend () -> AppResult<LocalDocumentFile?> = { AppResult.Success(file) }
    var delete: suspend () -> Boolean = { true }
    val files =
        object : DocumentFileRepository {
            override suspend fun reserveDownload(
                access: DocumentAccess,
                draft: DocumentFileDraft,
                maxBytes: Long,
                maxFiles: Int,
            ): AppResult<DocumentFileReservation> =
                error("This operation never reserves a download")

            override suspend fun appendDownload(
                access: DocumentAccess,
                file: LocalDocumentFile,
                bytes: ByteArray,
            ): AppResult<LocalDocumentFile?> = error("This operation never writes download content")

            override suspend fun completeDownload(
                access: DocumentAccess,
                file: LocalDocumentFile,
            ): AppResult<DocumentFileCompletion> =
                error("This operation never completes a download")

            override suspend fun replaceUpload(
                access: DocumentAccess,
                expected: LocalDocumentFile,
                upload: DocumentUpload,
            ): AppResult<Boolean> = error("This flow never replaces an upload intent")

            override suspend fun updateUpload(
                access: DocumentAccess,
                expected: LocalDocumentFile,
                upload: DocumentUpload,
            ): AppResult<Boolean> = error("Read/removal never starts an upload")

            override suspend fun chunk(
                access: DocumentAccess,
                file: LocalDocumentFile,
                offset: Long,
            ): AppResult<DocumentFileChunk> = error("Read/removal never sends chunks")

            override suspend fun import(
                access: DocumentAccess,
                draft: DocumentFileDraft,
                maxBytes: Long,
                maxFiles: Int,
            ): AppResult<DocumentFileReservation> = error("Not importing")

            override fun observe(access: DocumentAccess) = flow {
                readers++
                try {
                    emitAll(rows)
                } finally {
                    readers--
                }
            }

            override suspend fun find(access: DocumentAccess, id: String) = lookup()

            override suspend fun remove(
                access: DocumentAccess,
                id: String,
                revision: Long,
            ): AppResult<Boolean> {
                removals++
                return AppResult.Success(delete())
            }

            override suspend fun discardIncomplete(
                access: DocumentAccess,
                id: String,
            ): AppResult<Boolean> = error("Not cleanup")
        }
    val sessions =
        object : SessionRepository by base.sessionRepository {
            override fun observe() = base.sessions
        }
    val workspaces =
        object : WorkspaceRepository by base.workspaceRepository {
            override fun observe(sessionId: String) = base.workspaces
        }
    private val build = AppBuild(AppPlatform.Android, 1, "1.0", null)
    val observe = ObserveDocumentFiles(files, sessions, workspaces, base.clock, build)
    val remove = RemoveDocumentFile(files, sessions, workspaces, base.clock, build)
}
