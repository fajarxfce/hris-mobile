package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_DOWNLOAD_CHUNK_BYTES
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.documents.domain.usecases.ExportDocumentFile
import kotlin.test.*

internal class DocumentExportFixture {
    val download = DocumentDownloadFixture()
    val base = download.base
    val evidence = download.evidence
    val file =
        LocalDocumentFile(
            DocumentFileDraft(
                "66666666-6666-4666-8666-666666666666",
                DocumentFileDeclaration(evidence.fileName, evidence.mediaType, evidence.size),
                base.now,
                DocumentDownloadSource(base.query.employmentId, base.query.classification, evidence),
            ),
            evidence.size,
            evidence.sha256,
            1,
        )
    var stored: LocalDocumentFile? = file
    val buffers = mutableListOf<ByteArray>()
    val reads = mutableListOf<Long>()
    val writes = mutableListOf<Long>()
    val selections = mutableListOf<String>()
    val releases = mutableListOf<String>()
    var completed = 0
    var select: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var write: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var complete: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var read: suspend (Long) -> AppResult<DocumentFileChunk> = { offset ->
        AppResult.Success(
            DocumentFileChunk(
                ByteArray(
                        minOf(DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(), evidence.size - offset)
                            .toInt()
                    ) {
                        42
                    }
                    .also { buffers += it },
                "a".repeat(64),
            )
        )
    }
    private val files =
        object : DocumentFileRepository by download.files {
            override suspend fun find(access: DocumentAccess, id: String) =
                AppResult.Success(stored)

            override suspend fun chunk(
                access: DocumentAccess,
                file: LocalDocumentFile,
                offset: Long,
            ): AppResult<DocumentFileChunk> {
                assertEquals(this@DocumentExportFixture.file, file)
                reads += offset
                return read(offset)
            }
        }
    private val outputs =
        object : DocumentOutputRepository {
            override suspend fun select(
                requestId: String,
                declaration: DocumentFileDeclaration,
            ): AppResult<Boolean> {
                assertEquals(file.draft.declaration, declaration)
                selections += requestId
                return select()
            }

            override suspend fun write(
                requestId: String,
                offset: Long,
                bytes: ByteArray,
            ): AppResult<Unit> {
                assertEquals(selections.last(), requestId)
                assertTrue(bytes.size in 1..DOCUMENT_DOWNLOAD_CHUNK_BYTES)
                assertTrue(bytes.all { it == 42.toByte() })
                writes += offset
                return write()
            }

            override suspend fun complete(requestId: String): AppResult<Unit> {
                assertEquals(selections.last(), requestId)
                completed++
                return complete()
            }

            override suspend fun release(requestId: String): AppResult<Unit> {
                releases += requestId
                return AppResult.Success(Unit)
            }
        }
    val export =
        ExportDocumentFile(
            files,
            download.content,
            outputs,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            AppBuild(AppPlatform.Android, 1, "1.0", null),
        )

    suspend fun export() = export(base.workspace.id, file.draft.id)

    fun revoke() {
        base.workspaces.value = AppResult.Success(base.workspace.copy(permissions = emptySet()))
    }
}
