package dev.fajar.starter.payroll.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.replica.policies.validateStoredPayrollReplica
import dev.fajar.starter.payroll.domain.replica.repositories.PayslipReplicaRepository
import dev.fajar.starter.payroll.domain.repositories.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Acquires one immutable revision. It never advances the feed or reopens a denied projection. */
class LoadPayslipRevision(
    private val remote: PayslipRepository,
    private val replicas: PayslipReplicaRepository,
    private val revisions: PayslipRevisionRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex = Mutex(),
) {
    suspend operator fun invoke(workspaceId: String, id: String, revision: Long): AppResult<Unit> =
        withTimeoutOrNull(30.seconds) {
            val valid = validatePayslipId(id)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            if (revision < 0)
                return@withTimeoutOrNull payslipFailure("client.payslip_revision_invalid")
            execution.withLock { acquire(workspaceId, id, revision) }
        } ?: payslipFailure("client.payslip_revision_timeout", FailureKind.Timeout)

    private suspend fun acquire(workspaceId: String, id: String, revision: Long): AppResult<Unit> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val head =
            when (val result = currentHead(workspaceId, access, id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (revision > head.summary.version)
            return payslipFailure("client.payslip_revision_invalid")
        val stored =
            if (revision == head.summary.version) AppResult.Success(head)
            else revisions.observeRevision(access, id, revision).first()
        currentCoroutineContext().ensureActive()
        val latest =
            when (val result = currentHead(workspaceId, access, id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        when (stored) {
            is AppResult.Failed -> return stored
            is AppResult.Success ->
                stored.value?.let {
                    return validatePayslipRevision(latest, revision, it)
                }
        }
        val requestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val policyOwner = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (policyOwner is AppResult.Failed) return policyOwner
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> return reject(access, policyResponse)
                is AppResult.Success -> CompanyPolicySnapshot(policyResponse.value, requestedAt)
            }
        val available = admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
        if (available is AppResult.Failed) return reject(access, available)
        val beforeRequest = currentHead(workspaceId, access, id)
        if (beforeRequest is AppResult.Failed) return beforeRequest
        val response = remote.revision(access, id, revision)
        currentCoroutineContext().ensureActive()
        val owner = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        val continued = admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
        if (continued is AppResult.Failed) return reject(access, continued)
        val current =
            when (val result = currentHead(workspaceId, access, id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val historical =
            when (response) {
                is AppResult.Failed -> return reject(access, response)
                is AppResult.Success -> response.value
            }
        val valid = validatePayslipRevision(current, revision, historical)
        if (valid is AppResult.Failed) return valid
        val saved = revisions.retainRevision(access, historical)
        currentCoroutineContext().ensureActive()
        val afterWrite = currentHead(workspaceId, access, id)
        if (afterWrite is AppResult.Failed) return afterWrite
        val publication = admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
        if (publication is AppResult.Failed) return reject(access, publication)
        return when (saved) {
            is AppResult.Failed -> saved
            is AppResult.Success ->
                if (saved.value) AppResult.Success(Unit)
                else payslipFailure("client.payslip_changed", FailureKind.Conflict)
        }
    }

    private suspend fun currentHead(
        workspaceId: String,
        access: PayrollAccess,
        id: String,
    ): AppResult<Payslip> {
        val ownership = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        when (val admission = replicas.admission(access)) {
            is AppResult.Failed -> return admission
            is AppResult.Success ->
                admission.value.denial?.let {
                    return AppResult.Failed(it)
                }
        }
        val stored = replicas.observeDetails(access, id).first()
        currentCoroutineContext().ensureActive()
        val afterRead = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (afterRead is AppResult.Failed) return afterRead
        when (val admission = replicas.admission(access)) {
            is AppResult.Failed -> return admission
            is AppResult.Success ->
                admission.value.denial?.let {
                    return AppResult.Failed(it)
                }
        }
        currentCoroutineContext().ensureActive()
        val finalOwner = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (finalOwner is AppResult.Failed) return finalOwner
        return when (stored) {
            is AppResult.Failed -> stored
            is AppResult.Success -> {
                val owner = validateStoredPayrollReplica(access, stored.value.checkpoint)
                if (owner is AppResult.Failed) owner
                else
                    stored.value.view
                        ?.payslip
                        ?.takeIf { stored.value.checkpoint?.published == true }
                        ?.let {
                            if (it.summary.id == id) AppResult.Success(it)
                            else payslipFailure("client.invalid_response", FailureKind.Unexpected)
                        }
                        ?: payslipFailure(
                            "client.payslip_revision_not_saved",
                            FailureKind.Unavailable,
                        )
            }
        }
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

    private suspend fun reject(access: PayrollAccess, failure: AppResult.Failed): AppResult.Failed {
        if (payslipFailureInvalidatesCache(failure.failure)) {
            withTimeoutOrNull(2.seconds) { replicas.restrict(access, failure.failure) }
            withTimeoutOrNull(2.seconds) { replicas.clear(access) }
        }
        return failure
    }
}
