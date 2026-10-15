@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.payroll.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentFileDeclaration
import dev.fajar.starter.documents.domain.repositories.DocumentOutputRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.replica.repositories.PayslipReplicaRepository
import dev.fajar.starter.payroll.domain.repositories.PayslipPdfRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** Explicit file disclosure; a selected destination never bypasses current payroll authority. */
class ExportPayslip(
    private val pdf: PayslipPdfRepository,
    private val outputs: DocumentOutputRepository,
    private val replicas: PayslipReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        id: String,
        revision: Long,
        language: PayslipDocumentLanguage,
    ): AppResult<Boolean> {
        val valid = validatePayslipId(id)
        if (valid is AppResult.Failed) return valid
        if (revision < 0) return payslipFailure("invalid_payslip_revision")
        val access =
            when (val result = withTimeoutOrNull(5.seconds) { currentAccess(workspaceId) }) {
                null -> return payslipFailure("client.payslip_export_timeout", FailureKind.Timeout)
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val result = exportFile(workspaceId, access, id, revision, language)
        currentCoroutineContext().ensureActive()
        val owner =
            withTimeoutOrNull(5.seconds) {
                validatePayrollContinuation(access, currentAccess(workspaceId))
            } ?: return payslipFailure("client.payslip_export_timeout", FailureKind.Timeout)
        if (owner is AppResult.Failed) return owner
        if (result is AppResult.Failed && payslipFailureInvalidatesCache(result.failure)) {
            withTimeoutOrNull(2.seconds) { replicas.restrict(access, result.failure) }
            withTimeoutOrNull(2.seconds) { replicas.clear(access) }
        }
        return result
    }

    private suspend fun exportFile(
        workspaceId: String,
        access: PayrollAccess,
        id: String,
        revision: Long,
        language: PayslipDocumentLanguage,
    ): AppResult<Boolean> {
        val metadata =
            when (
                val result =
                    withTimeoutOrNull(30.seconds) {
                        review(workspaceId, access, id, revision, language)
                    }
            ) {
                null -> return payslipFailure("client.payslip_export_timeout", FailureKind.Timeout)
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val requestId = Uuid.random().toString()
        try {
            val selected =
                withTimeoutOrNull(5.minutes) {
                    outputs.select(
                        requestId,
                        DocumentFileDeclaration(
                            "payslip-$id-v$revision-${language.name.lowercase()}.pdf",
                            "application/pdf",
                            metadata.size,
                        ),
                    )
                } ?: return payslipFailure("client.payslip_selection_timeout", FailureKind.Timeout)
            currentCoroutineContext().ensureActive()
            when (selected) {
                is AppResult.Failed -> return selected
                is AppResult.Success -> if (!selected.value) return AppResult.Success(false)
            }
            return withTimeoutOrNull(2.minutes) { copy(workspaceId, access, metadata, requestId) }
                ?: payslipFailure("client.payslip_export_timeout", FailureKind.Timeout)
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(5.seconds) { outputs.release(requestId) }
            }
        }
    }

    private suspend fun review(
        workspaceId: String,
        access: PayrollAccess,
        id: String,
        revision: Long,
        language: PayslipDocumentLanguage,
    ): AppResult<PayslipPdfMetadata> {
        val policy =
            when (val result = acquirePolicy(workspaceId, access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val metadata =
            when (val result = pdf.inspect(access, id, revision, language)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val continued = admitted(workspaceId, access, policy)
        if (continued is AppResult.Failed) return continued
        val valid = validatePayslipPdf(metadata)
        if (valid is AppResult.Failed) return valid
        if (
            metadata.payslipId != id ||
                metadata.revision != revision ||
                metadata.language != language
        )
            return payslipFailure("client.invalid_response", FailureKind.Unexpected)
        return AppResult.Success(metadata)
    }

    private suspend fun copy(
        workspaceId: String,
        access: PayrollAccess,
        metadata: PayslipPdfMetadata,
        requestId: String,
    ): AppResult<Boolean> {
        val policy =
            when (val result = acquirePolicy(workspaceId, access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        // The range request reauthorizes the resource after the possibly long-lived save dialog.
        val bytes =
            when (val result = pdf.download(access, metadata)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        try {
            currentCoroutineContext().ensureActive()
            val beforeWrite = admitted(workspaceId, access, policy)
            if (beforeWrite is AppResult.Failed) return beforeWrite
            if (bytes.size.toLong() != metadata.size)
                return payslipFailure("client.invalid_response", FailureKind.Unexpected)
            val written = outputs.write(requestId, 0, bytes)
            currentCoroutineContext().ensureActive()
            if (written is AppResult.Failed) return written
            val verified =
                when (
                    val result =
                        review(
                            workspaceId,
                            access,
                            metadata.payslipId,
                            metadata.revision,
                            metadata.language,
                        )
                ) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            if (verified != metadata)
                return payslipFailure("client.payslip_changed", FailureKind.Conflict)
            val completed = outputs.complete(requestId)
            currentCoroutineContext().ensureActive()
            val continuation = validatePayrollContinuation(access, currentAccess(workspaceId))
            if (continuation is AppResult.Failed) return continuation
            return when (completed) {
                is AppResult.Failed -> completed
                is AppResult.Success -> AppResult.Success(true)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun acquirePolicy(
        workspaceId: String,
        access: PayrollAccess,
    ): AppResult<CompanyPolicySnapshot> {
        val before = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (before is AppResult.Failed) return before
        val requestedAt = clock.now()
        val policy =
            when (val result = workspaces.clientPolicy(access.sessionId, access.companyId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> CompanyPolicySnapshot(result.value, requestedAt)
            }
        currentCoroutineContext().ensureActive()
        return when (val admission = admitted(workspaceId, access, policy)) {
            is AppResult.Failed -> admission
            is AppResult.Success -> AppResult.Success(policy)
        }
    }

    private suspend fun admitted(
        workspaceId: String,
        access: PayrollAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<Unit> {
        val ownership = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        return admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<PayrollAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return payslipFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitPayroll(workspaceId, workspace, latest, build, clock.now())
    }
}
