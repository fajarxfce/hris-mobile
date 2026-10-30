package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.documents.domain.usecases.ImportDocumentFile
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ImportDocumentFileTest {
    @Test
    fun importRequiresTheUploadGrantAndNeverStartsTransport() = runTest {
        val f = DocumentImportFixture()
        f.base.workspaces.value = AppResult.Success(f.base.workspace)
        assertEquals(FailureKind.AccessDenied, assertIs<AppResult.Failed>(f.import()).failure.kind)
        assertTrue(f.selectedIds.isEmpty())
        assertEquals(0, f.imports)
        f.allowUpload()
        val file = assertIs<AppResult.Success<LocalDocumentFile?>>(f.import()).value!!
        assertEquals(3L, file.storedBytes)
        assertEquals("a".repeat(64), file.sha256)
        assertEquals(f.selectedIds, f.releasedIds)
        assertEquals(file, f.retained)
        assertEquals(0, f.base.evidenceReads)
        assertEquals(0, f.base.policyReads, "Local import does not silently request transport")
    }

    @Test
    fun invalidProviderFactsAreRejectedBeforeStorageAndDismissalIsNotAnError() = runTest {
        val cases =
            listOf(
                DocumentFileSelection(null, "application/pdf", 3),
                DocumentFileSelection("../file.pdf", "application/pdf", 3),
                DocumentFileSelection("file\n.pdf", "application/pdf", 3),
                DocumentFileSelection("file.png", "application/pdf", 3),
                DocumentFileSelection("file.pdf", "text/html", 3),
                DocumentFileSelection("file.pdf", "application/pdf", null),
                DocumentFileSelection("file.pdf", "application/pdf", 0),
                DocumentFileSelection("file.pdf", "application/pdf", DOCUMENT_MAXIMUM_BYTES + 1),
            )
        for (input in cases) {
            val f = DocumentImportFixture()
            f.selection = { AppResult.Success(input) }
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.import()).failure.kind,
            )
            assertEquals(0, f.imports)
            assertEquals(f.selectedIds, f.releasedIds)
        }
        val f = DocumentImportFixture()
        f.selection = { AppResult.Success(null) }
        assertEquals(AppResult.Success(null), f.import())
        assertEquals(0, f.imports)
        assertEquals(f.selectedIds, f.releasedIds)
        assertEquals(
            AppResult.Success(DocumentFileDeclaration("photo.JPG", "image/jpeg", 3)),
            resolveDocumentFile(DocumentFileSelection("photo.JPG", null, 3)),
        )
    }

    @Test
    fun latePickerResultsAfterCancellationAreReleasedWithoutImporting() = runTest {
        val f = DocumentImportFixture()
        val opened = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Unit>()
        f.selection = {
            withContext(NonCancellable) {
                opened.complete(Unit)
                result.await()
                AppResult.Success(f.validSelection)
            }
        }
        val operation = async { f.import() }
        opened.await()
        operation.cancel()
        result.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(0, f.imports)
        assertEquals(f.selectedIds, f.releasedIds)
        assertEquals(f.selectedIds, f.cleanupIds)
    }

    @Test
    fun admissionIsCheckedAfterSelectionAndAfterImport() = runTest {
        val f = DocumentImportFixture()
        f.selection = {
            f.base.workspaces.value = AppResult.Success(f.base.workspace)
            AppResult.Success(f.validSelection)
        }
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.import()).failure.code)
        assertEquals(0, f.imports)
        f.allowUpload()
        f.selection = { AppResult.Success(f.validSelection) }
        f.copyFile = { draft ->
            f.base.sessions.value = AppResult.Success(null)
            f.storeCompleted(draft)
        }
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.import()).failure.kind)
        assertNotNull(f.retained?.sha256, "A completed file stays recoverable for its owner")
        assertEquals(f.selectedIds, f.releasedIds)
    }

    @Test
    fun cancellationAfterACommittedFileDoesNotDeleteTheCompletedImport() = runTest {
        val f = DocumentImportFixture()
        val copying = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        f.copyFile = { draft ->
            withContext(NonCancellable) {
                copying.complete(Unit)
                commit.await()
                f.storeCompleted(draft)
            }
        }
        val operation = async { f.import() }
        copying.await()
        operation.cancel()
        commit.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertNotNull(f.retained?.sha256)
        assertEquals(f.selectedIds, f.releasedIds)
        assertEquals(f.selectedIds, f.cleanupIds)
    }

    @Test
    fun pendingImportsHaveADeadlineAndFailedImportsKeepTheirFailureKind() = runTest {
        val f = DocumentImportFixture()
        f.copyFile = { draft ->
            f.retained = LocalDocumentFile(draft, 0, null, 0)
            awaitCancellation()
        }
        val timed = async { f.import() }
        runCurrent()
        advanceTimeBy(2.minutes.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(
            "client.document_import_timeout",
            assertIs<AppResult.Failed>(timed.await()).failure.code,
        )
        assertNull(f.retained)
        assertEquals(f.selectedIds, f.releasedIds)
        val original =
            AppResult.Failed(
                Failure(FailureKind.Permission, "Unavailable", code = "client.file_permission")
            )
        f.copyFile = { original }
        assertEquals(original, f.import())
        f.copyFile = { AppResult.Success(DocumentFileReservation.CapacityExceeded) }
        assertEquals(
            "client.document_storage_full",
            assertIs<AppResult.Failed>(f.import()).failure.code,
        )
    }
}

private class DocumentImportFixture {
    val base = DocumentFixture()
    val validSelection = DocumentFileSelection("file.pdf", "application/pdf", 3)
    val selectedIds = mutableListOf<String>()
    val releasedIds = mutableListOf<String>()
    val cleanupIds = mutableListOf<String>()
    var imports = 0
    var retained: LocalDocumentFile? = null
    var selection: suspend () -> AppResult<DocumentFileSelection?> = {
        AppResult.Success(validSelection)
    }
    var copyFile: suspend (DocumentFileDraft) -> AppResult<DocumentFileReservation> =
        ::storeCompleted

    init {
        allowUpload()
    }

    fun allowUpload() {
        base.workspaces.value =
            AppResult.Success(
                base.workspace.copy(
                    permissions = setOf("documents.self.read", "documents.self.upload")
                )
            )
    }

    fun storeCompleted(draft: DocumentFileDraft): AppResult<DocumentFileReservation> {
        val file = LocalDocumentFile(draft, draft.declaration.size, "a".repeat(64), 2)
        retained = file
        return AppResult.Success(DocumentFileReservation.Stored(file))
    }

    val inputRepository =
        object : DocumentInputRepository {
            override suspend fun select(
                requestId: String,
                mediaTypes: Set<String>,
            ): AppResult<DocumentFileSelection?> {
                assertEquals(DOCUMENT_INPUT_TYPES, mediaTypes)
                selectedIds += requestId
                return selection()
            }

            override suspend fun release(requestId: String): AppResult<Unit> {
                releasedIds += requestId
                return AppResult.Success(Unit)
            }
        }
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
            ): AppResult<Boolean> = error("Import never starts an upload")

            override suspend fun chunk(
                access: DocumentAccess,
                file: LocalDocumentFile,
                offset: Long,
            ): AppResult<DocumentFileChunk> = error("Import never sends chunks")

            override suspend fun import(
                access: DocumentAccess,
                draft: DocumentFileDraft,
                maxBytes: Long,
                maxFiles: Int,
            ): AppResult<DocumentFileReservation> {
                assertEquals(DOCUMENT_LOCAL_CAPACITY, maxBytes)
                assertEquals(DOCUMENT_LOCAL_FILES, maxFiles)
                imports++
                return copyFile(draft)
            }

            override fun observe(access: DocumentAccess) =
                flowOf(AppResult.Success(listOfNotNull(retained)))

            override suspend fun find(access: DocumentAccess, id: String) =
                AppResult.Success(retained?.takeIf { it.draft.id == id })

            override suspend fun remove(
                access: DocumentAccess,
                id: String,
                revision: Long,
            ): AppResult<Boolean> = error("Import cleanup must use discardIncomplete")

            override suspend fun discardIncomplete(
                access: DocumentAccess,
                id: String,
            ): AppResult<Boolean> {
                cleanupIds += id
                if (retained?.sha256 != null) return AppResult.Success(false)
                retained = null
                return AppResult.Success(true)
            }
        }
    private val useCase =
        ImportDocumentFile(
            inputRepository,
            files,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            AppBuild(AppPlatform.Android, 1, "1.0", null),
        )

    suspend fun import() = useCase(base.workspace.id)
}
