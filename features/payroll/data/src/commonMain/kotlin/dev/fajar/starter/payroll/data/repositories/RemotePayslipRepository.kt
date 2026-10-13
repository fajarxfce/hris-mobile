@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.payroll.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.fileio.Sha256Digest
import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.payroll.data.datasources.PayrollRemoteDataSource
import dev.fajar.starter.payroll.data.mappers.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.repositories.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.Single

@Single
class RemotePayslipRepository(private val remote: PayrollRemoteDataSource) :
    PayslipRepository, PayslipPdfRepository {
    override suspend fun list(access: PayrollAccess, selection: PayslipSelection) = safeApiCall {
        require(Uuid.parse(access.companyId).toString() == access.companyId)
        require(validatePayslipSelection(selection) is AppResult.Success)
        val year = requireNotNull(selection.year)
        remote
            .payslips(access.sessionId, access.companyId, "$year-01", "$year-12", selection.after)
            .toPage(selection)
    }

    override suspend fun details(access: PayrollAccess, id: String) = safeApiCall {
        listOf(access.companyId, id).forEach { require(Uuid.parse(it).toString() == it) }
        remote.payslip(access.sessionId, access.companyId, id).toPayslip(id)
    }

    override suspend fun payments(access: PayrollAccess, id: String) = safeApiCall {
        listOf(access.companyId, id).forEach { require(Uuid.parse(it).toString() == it) }
        remote.payments(access.sessionId, access.companyId, id).toPayments(id)
    }

    override suspend fun inspect(
        access: PayrollAccess,
        id: String,
        language: PayslipDocumentLanguage,
    ) = safeApiCall {
        listOf(access.companyId, id).forEach { require(Uuid.parse(it).toString() == it) }
        val headers = remote.inspectPdf(access.sessionId, access.companyId, id, language.name)
        require(headers.mediaTypes.singleOrNull()?.lowercase() == "application/pdf")
        require(headers.encodings.isEmpty() || headers.encodings == listOf("identity"))
        val metadata =
            PayslipPdfMetadata(
                id,
                language,
                requireNotNull(headers.lengths.singleOrNull()?.toLongOrNull()),
                requireNotNull(headers.entityTags.singleOrNull()),
            )
        require(validatePayslipPdf(metadata) is AppResult.Success)
        metadata
    }

    override suspend fun download(
        access: PayrollAccess,
        metadata: PayslipPdfMetadata,
    ): AppResult<ByteArray> {
        var acquired: ByteArray? = null
        return try {
            val result = safeApiCall {
                require(Uuid.parse(access.companyId).toString() == access.companyId)
                require(validatePayslipPdf(metadata) is AppResult.Success)
                val bytes =
                    remote.downloadPdf(
                        access.sessionId,
                        access.companyId,
                        metadata.payslipId,
                        metadata.language.name,
                        metadata.entityTag,
                        metadata.size,
                    )
                acquired = bytes
                currentCoroutineContext().ensureActive()
                require(
                    bytes.size.toLong() == metadata.size &&
                        bytes.take(5).toByteArray().decodeToString() == "%PDF-"
                )
                val digest = Sha256Digest().apply { update(bytes) }.finishHex()
                require(metadata.entityTag == "\"$digest\"")
                bytes
            }
            if (result is AppResult.Success) acquired = null
            result
        } finally {
            acquired?.fill(0)
        }
    }
}
