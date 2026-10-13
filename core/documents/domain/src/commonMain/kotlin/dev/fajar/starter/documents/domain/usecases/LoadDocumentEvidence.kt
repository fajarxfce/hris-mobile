package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.DocumentRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.policies.companyPolicyDeadline
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

class LoadDocumentEvidence(
    private val remote: DocumentRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        query: DocumentEvidenceQuery,
    ): AppResult<DocumentEvidenceSnapshot> =
        withTimeoutOrNull(30.seconds) {
            val valid = validateDocumentEvidenceQuery(query)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val requestedAt = clock.now()
            val response = workspaces.clientPolicy(access.sessionId, access.companyId)
            currentCoroutineContext().ensureActive()
            val admission = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (admission is AppResult.Failed) return@withTimeoutOrNull admission
            val policy =
                when (response) {
                    is AppResult.Failed -> return@withTimeoutOrNull response
                    is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
                }
            val available =
                admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
            if (available is AppResult.Failed) return@withTimeoutOrNull available
            val acquired = remote.evidence(access, query)
            currentCoroutineContext().ensureActive()
            val publication =
                when (
                    val result = validateDocumentContinuation(access, currentAccess(workspaceId))
                ) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val stillAvailable =
                admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
            if (stillAvailable is AppResult.Failed) return@withTimeoutOrNull stillAvailable
            when (acquired) {
                is AppResult.Failed -> acquired
                is AppResult.Success -> {
                    val policyDeadline = companyPolicyDeadline(policy)
                    val maintenance =
                        policy.policy.maintenance
                            ?.let { policy.requestedAt + (it.startsAt - policy.policy.serverTime) }
                            ?.takeIf { it > clock.now() }
                    val expiry =
                        minOf(
                            requestedAt + DOCUMENT_EVIDENCE_PERIOD,
                            publication.authorizationExpiresAt,
                            publication.sessionExpiresAt,
                            policyDeadline,
                            maintenance ?: publication.authorizationExpiresAt,
                        )
                    if (expiry <= clock.now())
                        documentFailure(
                            "client.document_evidence_expired",
                            FailureKind.AccessDenied,
                        )
                    else
                        AppResult.Success(
                            DocumentEvidenceSnapshot(
                                publication,
                                query,
                                acquired.value,
                                requestedAt,
                                expiry,
                            )
                        )
                }
            }
        } ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    private suspend fun currentAccess(workspaceId: String): AppResult<DocumentAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val workspace =
            if (session == null) null
            else
                when (val result = workspaces.current(session.id)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
        currentCoroutineContext().ensureActive()
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitDocumentCatalog(workspaceId, workspace, latest, build, clock.now())
    }
}
