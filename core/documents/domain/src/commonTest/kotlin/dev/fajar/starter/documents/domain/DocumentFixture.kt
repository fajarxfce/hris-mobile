package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.repositories.DocumentRepository
import dev.fajar.starter.documents.domain.usecases.LoadDocumentEvidence
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

class DocumentFixture {
    var now = Instant.parse("2026-10-12T01:00:00Z")
    val clock =
        object : Clock {
            override fun now() = this@DocumentFixture.now
        }
    val session =
        Session(
            "session",
            User("11111111-1111-1111-1111-111111111111", "Alex", "alex@example.test"),
            SessionTokens("access", "refresh", Long.MAX_VALUE),
            SessionBinding(
                "family",
                "https://hris.example.test/",
                (now + 24.hours).toEpochMilliseconds(),
            ),
        )
    val policy =
        CompanyPolicy(
            null,
            setOf(CompanyModule.DOCUMENTS),
            CompanyMinimumBuilds(0, 0, 0),
            null,
            false,
            now,
            now + 60.seconds,
        )
    val workspace =
        Workspace(
            "workspace",
            session.id,
            session.binding.audience,
            CompanyMembership(
                "22222222-2222-2222-2222-222222222222",
                "Company",
                "CO",
                "Asia/Jakarta",
            ),
            setOf("documents.self.read"),
            WorkspaceAdmission(session.user.id, session.binding.serverId, now, now + 1.hours),
            policy = CompanyPolicySnapshot(policy, now),
        )
    val query =
        DocumentEvidenceQuery(
            "33333333-3333-3333-3333-333333333333",
            DocumentClassification.PERSONAL,
        )
    val evidence =
        DocumentEvidence(
            "44444444-4444-4444-4444-444444444444",
            "55555555-5555-5555-5555-555555555555",
            1,
            "Evidence",
            "evidence.pdf",
            "application/pdf",
            100,
            "a".repeat(64),
            now,
        )
    val page = DocumentEvidencePage(listOf(evidence))
    val sessions = MutableStateFlow<AppResult<Session?>>(AppResult.Success(session))
    val workspaces = MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(workspace))
    var policyReads = 0
    var evidenceReads = 0
    var policyCall: suspend () -> AppResult<CompanyPolicy> = { AppResult.Success(policy) }
    var evidenceCall: suspend () -> AppResult<DocumentEvidencePage> = { AppResult.Success(page) }
    var employmentCall: suspend () -> AppResult<DocumentEmploymentPage> = {
        AppResult.Success(
            DocumentEmploymentPage(
                listOf(
                    DocumentEmployment(
                        query.employmentId,
                        "EMP-001",
                        DocumentEmploymentStatus.ACTIVE,
                        0,
                    )
                ),
                "2026-10-12",
                "Asia/Jakarta",
                null,
            )
        )
    }
    val sessionRepository =
        object : SessionRepository {
            override val persistent = false

            override fun observe(): Flow<AppResult<Session?>> = sessions

            override suspend fun current() = sessions.value

            override suspend fun compareAndSet(
                expected: Session?,
                updated: Session?,
            ): AppResult<Boolean> = error("Read-only")

            override suspend fun clear(sessionId: String): AppResult<Boolean> = error("Read-only")
        }
    val workspaceRepository =
        object : WorkspaceRepository {
            override fun observe(sessionId: String): Flow<AppResult<Workspace?>> = workspaces

            override suspend fun current(sessionId: String) = workspaces.value

            override suspend fun compareAndSet(
                sessionId: String,
                expected: Workspace?,
                updated: Workspace?,
            ): AppResult<Boolean> = error("Read-only")

            override suspend fun permissions(
                sessionId: String,
                companyId: String,
            ): AppResult<Set<String>> = error("Use the admitted scope")

            override suspend fun clientPolicy(
                sessionId: String,
                companyId: String,
            ): AppResult<CompanyPolicy> {
                policyReads++
                return policyCall()
            }
        }
    val repository =
        object : DocumentRepository {
            override suspend fun record(
                access: DocumentAccess,
                id: String,
            ): AppResult<
                dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogRecord
            > = error("This evidence selector uses its own live review")

            override suspend fun employments(access: DocumentAccess, after: String?) =
                employmentCall()

            override suspend fun evidence(
                access: DocumentAccess,
                query: DocumentEvidenceQuery,
            ): AppResult<DocumentEvidencePage> {
                evidenceReads++
                return evidenceCall()
            }
        }
    val load =
        LoadDocumentEvidence(
            repository,
            sessionRepository,
            workspaceRepository,
            clock,
            AppBuild(AppPlatform.Android, 1, "1.0", null),
        )
}
