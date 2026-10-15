package dev.fajar.starter.payroll.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentFileDeclaration
import dev.fajar.starter.documents.domain.repositories.DocumentOutputRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.repositories.PayslipPdfRepository
import dev.fajar.starter.payroll.domain.usecases.ExportPayslip

class PayslipExportFixture(val base: PayrollFixture = PayrollFixture()) {
    val bytes = "%PDF-1.4\nfixture\n%%EOF".encodeToByteArray()
    var metadata =
        PayslipPdfMetadata(
            base.summary.id,
            PayslipDocumentLanguage.ID,
            bytes.size.toLong(),
            "\"${"a".repeat(64)}\"",
            base.summary.version,
        )
    var inspectCall: suspend () -> AppResult<PayslipPdfMetadata> = { AppResult.Success(metadata) }
    var downloadCall: suspend () -> AppResult<ByteArray> = { AppResult.Success(bytes) }
    var selectCall: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var writeCall: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var releaseCall: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    val inspectedRevisions = mutableListOf<Long>()
    val downloadedRevisions = mutableListOf<Long>()
    var inspections = 0
    var downloads = 0
    var selections = 0
    var writes = 0
    var completions = 0
    var releases = 0
    var declaration: DocumentFileDeclaration? = null
    var written: ByteArray? = null
    var requestId: String? = null
    val pdf =
        object : PayslipPdfRepository {
            override suspend fun inspect(
                access: PayrollAccess,
                id: String,
                revision: Long,
                language: PayslipDocumentLanguage,
            ): AppResult<PayslipPdfMetadata> {
                inspections++
                inspectedRevisions += revision
                return inspectCall()
            }

            override suspend fun download(
                access: PayrollAccess,
                metadata: PayslipPdfMetadata,
            ): AppResult<ByteArray> {
                downloads++
                downloadedRevisions += metadata.revision
                return downloadCall()
            }
        }
    val output =
        object : DocumentOutputRepository {
            override suspend fun select(
                requestId: String,
                declaration: DocumentFileDeclaration,
            ): AppResult<Boolean> {
                selections++
                this@PayslipExportFixture.requestId = requestId
                this@PayslipExportFixture.declaration = declaration
                return selectCall()
            }

            override suspend fun write(
                requestId: String,
                offset: Long,
                bytes: ByteArray,
            ): AppResult<Unit> {
                check(requestId == this@PayslipExportFixture.requestId && offset == 0L)
                writes++
                written = bytes.copyOf()
                return writeCall()
            }

            override suspend fun complete(requestId: String): AppResult<Unit> {
                check(requestId == this@PayslipExportFixture.requestId)
                completions++
                return AppResult.Success(Unit)
            }

            override suspend fun release(requestId: String): AppResult<Unit> {
                check(requestId == this@PayslipExportFixture.requestId)
                releases++
                return releaseCall()
            }
        }
    val export =
        ExportPayslip(
            pdf,
            output,
            base.replicas,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )

    suspend fun save(revision: Long = base.summary.version) =
        export(base.workspace.id, base.summary.id, revision, PayslipDocumentLanguage.ID)
}
