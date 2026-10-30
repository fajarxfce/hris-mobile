package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.policies.initialDocumentCatalogCheckpoint
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.documents.domain.usecases.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

internal class DocumentDownloadFixture {
    val base = DocumentFixture()
    private val build = AppBuild(AppPlatform.Android, 1, "1.0", null)
    val access =
        (admitDocumentCatalog(base.workspace.id, base.workspace, base.session, build, base.now)
                as AppResult.Success)
            .value
    val evidence = base.evidence.copy(size = DOCUMENT_DOWNLOAD_CHUNK_BYTES * 2L + 3)
    val catalog =
        DocumentCatalogSnapshot(
            initialDocumentCatalogCheckpoint(
                    access,
                    "generation",
                    base.now,
                    listOf(
                        DocumentEmployment(
                            base.query.employmentId,
                            "EMP-001",
                            DocumentEmploymentStatus.ACTIVE,
                            0,
                        )
                    ),
                )
                .copy(
                    revision = 1,
                    phase = DocumentCatalogSyncPhase.CHANGES,
                    cursor = "head",
                    published = true,
                    refreshedAt = base.now,
                ),
            DocumentCatalogSelection(base.query.employmentId, base.query.classification),
            DocumentCatalogPage(
                listOf(
                    DocumentCatalogRecord(
                        evidence.documentId,
                        0,
                        base.query.employmentId,
                        base.query.classification,
                        evidence,
                    )
                )
            ),
        )
    val rows = MutableStateFlow<List<LocalDocumentFile>>(emptyList())
    val buffers = mutableListOf<ByteArray>()
    val offsets = mutableListOf<Long>()
    var inspections = 0
    var reservations = 0
    var completions = 0
    var writes = 0
    var inspect: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var read: suspend (Long) -> AppResult<ByteArray> = { offset ->
        AppResult.Success(
            ByteArray(
                    minOf(DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(), evidence.size - offset).toInt()
                ) {
                    42
                }
                .also { buffers += it }
        )
    }
    var beforeReserve: suspend () -> Unit = {}
    var beforeAppend: suspend () -> Unit = {}
    var afterAppend: suspend () -> Unit = {}
    var append: (LocalDocumentFile, ByteArray) -> LocalDocumentFile = { file, bytes ->
        file.copy(storedBytes = file.storedBytes + bytes.size, revision = file.revision + 1)
    }
    var complete: (LocalDocumentFile) -> DocumentFileCompletion = { file ->
        DocumentFileCompletion.Ready(
            file.copy(sha256 = evidence.sha256, revision = file.revision + 1)
        )
    }
    val files =
        object : DocumentFileRepository {
            override fun observe(access: DocumentAccess) = rows.map { AppResult.Success(it) }

            override suspend fun find(access: DocumentAccess, id: String) =
                AppResult.Success(rows.value.firstOrNull { it.draft.id == id })

            override suspend fun reserveDownload(
                access: DocumentAccess,
                draft: DocumentFileDraft,
                maxBytes: Long,
                maxFiles: Int,
            ): AppResult<DocumentFileReservation> {
                reservations++
                beforeReserve()
                val file = LocalDocumentFile(draft, 0, null, 0)
                rows.value += file
                return AppResult.Success(DocumentFileReservation.Stored(file))
            }

            override suspend fun appendDownload(
                access: DocumentAccess,
                file: LocalDocumentFile,
                bytes: ByteArray,
            ): AppResult<LocalDocumentFile?> {
                writes++
                beforeAppend()
                if (rows.value.none { it == file }) return AppResult.Success(null)
                val next = append(file, bytes)
                rows.value = rows.value.map { if (it.draft.id == next.draft.id) next else it }
                afterAppend()
                return AppResult.Success(next)
            }

            override suspend fun completeDownload(
                access: DocumentAccess,
                file: LocalDocumentFile,
            ): AppResult<DocumentFileCompletion> {
                completions++
                val outcome = complete(file)
                if (outcome is DocumentFileCompletion.Ready)
                    rows.value = rows.value.map { if (it == file) outcome.file else it }
                return AppResult.Success(outcome)
            }

            override suspend fun remove(
                access: DocumentAccess,
                id: String,
                revision: Long,
            ): AppResult<Boolean> {
                val found = rows.value.any { it.draft.id == id && it.revision == revision }
                if (found) rows.value = rows.value.filterNot { it.draft.id == id }
                return AppResult.Success(found)
            }

            override suspend fun import(
                access: DocumentAccess,
                draft: DocumentFileDraft,
                maxBytes: Long,
                maxFiles: Int,
            ): AppResult<DocumentFileReservation> = error("Download never acquires input")

            override suspend fun updateUpload(
                access: DocumentAccess,
                expected: LocalDocumentFile,
                upload: DocumentUpload,
            ): AppResult<Boolean> = error("Download never uploads")

            override suspend fun chunk(
                access: DocumentAccess,
                file: LocalDocumentFile,
                offset: Long,
            ): AppResult<DocumentFileChunk> =
                error("Download completion verifies content through its repository")

            override suspend fun discardIncomplete(
                access: DocumentAccess,
                id: String,
            ): AppResult<Boolean> = error("Download cancellation retains committed blocks")
        }
    val content =
        object : DocumentContentRepository {
            override suspend fun inspect(
                access: DocumentAccess,
                evidence: DocumentEvidence,
            ): AppResult<Unit> {
                inspections++
                return this@DocumentDownloadFixture.inspect()
            }

            override suspend fun chunk(
                access: DocumentAccess,
                evidence: DocumentEvidence,
                offset: Long,
            ): AppResult<ByteArray> {
                offsets += offset
                return read(offset)
            }
        }
    val execution = Mutex()
    val prepare =
        PrepareDocumentDownload(
            files,
            content,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            build,
            execution,
        )
    val download =
        DownloadDocumentFile(
            files,
            content,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            build,
            execution,
        )

    suspend fun prepare(): AppResult<LocalDocumentFile> =
        prepare(base.workspace.id, catalog, evidence.revisionId)

    suspend fun download(file: LocalDocumentFile): AppResult<LocalDocumentFile> =
        download(base.workspace.id, file.draft.id)
}
