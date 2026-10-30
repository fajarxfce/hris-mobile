package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.documents.domain.usecases.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

class DocumentUploadFixture(size: Long = DOCUMENT_UPLOAD_CHUNK_BYTES + 7L) {
    val base = DocumentFixture()
    val build = AppBuild(AppPlatform.Android, 1, "1.0", null)
    val lock = Mutex()
    val file =
        LocalDocumentFile(
            DocumentFileDraft(
                "66666666-6666-6666-6666-666666666666",
                DocumentFileDeclaration("evidence.pdf", "application/pdf", size),
                base.now,
            ),
            size,
            "a".repeat(64),
            20,
        )
    val rows = MutableStateFlow(listOf(file))
    val input =
        DocumentUploadInput(
            base.query.employmentId,
            "Personal document",
            DocumentClassification.PERSONAL,
            "Supporting document",
        )
    var beforeUpdate: suspend (LocalDocumentFile, DocumentUpload) -> Unit = { _, _ -> }
    var afterUpdate: suspend (LocalDocumentFile) -> Unit = {}
    var beforeChunk: suspend () -> Unit = {}
    val sent = mutableListOf<String>()
    val buffers = mutableListOf<ByteArray>()
    val schedules = mutableListOf<String>()
    var startCall: suspend (DocumentUploadIntent) -> AppResult<DocumentMutationReceipt> = {
        AppResult.Success(DocumentMutationReceipt(it.revisionId, 0))
    }
    var chunkCall: suspend (String, Long) -> AppResult<DocumentMutationReceipt> = { id, offset ->
        AppResult.Success(DocumentMutationReceipt(id, offset / DOCUMENT_UPLOAD_CHUNK_BYTES + 1))
    }
    var validationCall: suspend (String, Long) -> AppResult<DocumentMutationReceipt> =
        { id, version ->
            AppResult.Success(DocumentMutationReceipt(id, version + 1))
        }
    var status =
        DocumentRevisionProgress(
            DocumentRevisionStatus.READY,
            size,
            (size + DOCUMENT_UPLOAD_CHUNK_BYTES - 1) / DOCUMENT_UPLOAD_CHUNK_BYTES + 2,
            base.now + 24.hours,
            1,
            null,
        )
    var revisionCall: suspend () -> AppResult<DocumentRevisionProgress> = {
        AppResult.Success(status)
    }

    init {
        refreshAccess()
        base.policyCall = {
            AppResult.Success(
                base.policy.copy(serverTime = base.now, validUntil = base.now + 60.seconds)
            )
        }
    }

    fun advanceClock(duration: Duration) {
        base.now += duration
        refreshAccess()
    }

    private fun refreshAccess() {
        base.workspaces.value =
            AppResult.Success(
                base.workspace.copy(
                    permissions = setOf("documents.self.read", "documents.self.upload"),
                    policy =
                        CompanyPolicySnapshot(
                            base.policy.copy(
                                serverTime = base.now,
                                validUntil = base.now + 60.seconds,
                            ),
                            base.now,
                        ),
                )
            )
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

            override fun observe(access: DocumentAccess) = rows.map { AppResult.Success(it) }

            override suspend fun find(access: DocumentAccess, id: String) =
                AppResult.Success(rows.value.firstOrNull { it.draft.id == id })

            override suspend fun replaceUpload(
                access: DocumentAccess,
                expected: LocalDocumentFile,
                upload: DocumentUpload,
            ): AppResult<Boolean> {
                beforeUpdate(expected, upload)
                val actual = rows.value.firstOrNull { it.draft.id == expected.draft.id }
                if (actual != expected || actual.upload == null) return AppResult.Success(false)
                val saved = actual.copy(revision = actual.revision + 1, upload = upload)
                rows.value = rows.value.map { if (it.draft.id == saved.draft.id) saved else it }
                afterUpdate(saved)
                return AppResult.Success(true)
            }

            override suspend fun updateUpload(
                access: DocumentAccess,
                expected: LocalDocumentFile,
                upload: DocumentUpload,
            ): AppResult<Boolean> {
                beforeUpdate(expected, upload)
                val actual = rows.value.firstOrNull { it.draft.id == expected.draft.id }
                if (
                    actual?.revision != expected.revision ||
                        (actual.upload != null && actual.upload.intent != upload.intent)
                )
                    return AppResult.Success(false)
                val saved = actual.copy(revision = actual.revision + 1, upload = upload)
                rows.value = rows.value.map { if (it.draft.id == saved.draft.id) saved else it }
                afterUpdate(saved)
                return AppResult.Success(true)
            }

            override suspend fun chunk(
                access: DocumentAccess,
                file: LocalDocumentFile,
                offset: Long,
            ): AppResult<DocumentFileChunk> {
                beforeChunk()
                val bytes =
                    ByteArray(
                        minOf(
                                DOCUMENT_UPLOAD_CHUNK_BYTES.toLong(),
                                file.draft.declaration.size - offset,
                            )
                            .toInt()
                    ) {
                        42
                    }
                buffers += bytes
                return AppResult.Success(DocumentFileChunk(bytes, "b".repeat(64)))
            }

            override suspend fun remove(
                access: DocumentAccess,
                id: String,
                revision: Long,
            ): AppResult<Boolean> {
                val present = rows.value.any { it.draft.id == id && it.revision == revision }
                if (present) rows.value = rows.value.filterNot { it.draft.id == id }
                return AppResult.Success(present)
            }

            override suspend fun import(
                access: DocumentAccess,
                draft: DocumentFileDraft,
                maxBytes: Long,
                maxFiles: Int,
            ): AppResult<DocumentFileReservation> = error("Already imported")

            override suspend fun discardIncomplete(
                access: DocumentAccess,
                id: String,
            ): AppResult<Boolean> = error("Completed file")
        }
    val remote =
        object : DocumentUploadRepository {
            override suspend fun start(
                access: DocumentAccess,
                file: LocalDocumentFile,
                intent: DocumentUploadIntent,
                operationId: String,
            ): AppResult<DocumentMutationReceipt> {
                assertEquals(
                    intent,
                    rows.value.first { it.draft.id == file.draft.id }.upload?.intent,
                )
                assertTrue(
                    requireNotNull(rows.value.first { it.draft.id == file.draft.id }.upload)
                        .attempts > 0
                )
                sent += "start:$operationId"
                return startCall(intent)
            }

            override suspend fun chunk(
                access: DocumentAccess,
                revisionId: String,
                operationId: String,
                offset: Long,
                chunk: DocumentFileChunk,
            ): AppResult<DocumentMutationReceipt> {
                assertTrue(chunk.bytes.isNotEmpty() && chunk.bytes.all { it == 42.toByte() })
                sent += "chunk:$operationId:$offset:${chunk.bytes.size}:${chunk.sha256}"
                return chunkCall(revisionId, offset)
            }

            override suspend fun validate(
                access: DocumentAccess,
                revisionId: String,
                operationId: String,
                version: Long,
                reason: String,
            ): AppResult<DocumentMutationReceipt> {
                sent += "validate:$operationId:$version:$reason"
                return validationCall(revisionId, version)
            }

            override suspend fun cancel(
                access: DocumentAccess,
                revisionId: String,
                operationId: String,
                version: Long,
                reason: String,
            ): AppResult<DocumentMutationReceipt> {
                sent += "cancel:$operationId:$version:$reason"
                status =
                    status.copy(status = DocumentRevisionStatus.CANCELLED, version = version + 1)
                return AppResult.Success(DocumentMutationReceipt(revisionId, version + 1))
            }

            override suspend fun revision(
                access: DocumentAccess,
                file: LocalDocumentFile,
                intent: DocumentUploadIntent,
            ): AppResult<DocumentRevisionProgress> {
                sent += "status"
                return revisionCall()
            }
        }
    val schedule =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                assertNotNull(rows.value.single().upload)
                schedules += key
                return AppResult.Success(Unit)
            }
        }
    val load =
        LoadDocumentUploadPreparation(
            files,
            base.repository,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            build,
        )
    val prepare =
        PrepareDocumentUpload(
            files,
            base.sessionRepository,
            base.workspaceRepository,
            schedule,
            base.clock,
            build,
        )
    val replay = newReplayer()

    fun newReplayer() =
        ReplayDocumentUpload(
            files,
            remote,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            build,
            lock,
            schedule,
        )

    val review =
        ReviewDocumentUpload(
            files,
            remote,
            base.sessionRepository,
            base.workspaceRepository,
            schedule,
            base.clock,
            build,
            lock,
        )
    val remove =
        RemoveDocumentFile(
            files,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            build,
        )

    suspend fun confirmed(): LocalDocumentFile {
        val loaded =
            assertIs<AppResult.Success<DocumentUploadPreparation>>(
                    load(base.workspace.id, file.draft.id)
                )
                .value
        return assertIs<AppResult.Success<LocalDocumentFile>>(
                prepare(base.workspace.id, loaded, input)
            )
            .value
    }

    suspend fun replay() = replay(base.workspace.id, file.draft.id)
}
