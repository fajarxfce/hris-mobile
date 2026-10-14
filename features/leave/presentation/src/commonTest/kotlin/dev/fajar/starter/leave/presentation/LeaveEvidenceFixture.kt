package dev.fajar.starter.leave.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.repositories.DocumentRepository
import dev.fajar.starter.documents.domain.usecases.LoadDocumentEvidence
import dev.fajar.starter.leave.presentation.evidence.*
import kotlin.time.Instant

class LeaveEvidenceFixture(instant: () -> Instant) {
    val base = LeavePresentationFixture(instant)
    val target = LeaveEvidenceTarget(base.workspace.id, base.employee.id, emptySet())
    val queries = mutableListOf<DocumentEvidenceQuery>()
    var read: suspend (DocumentEvidenceQuery) -> AppResult<DocumentEvidencePage> = {
        AppResult.Success(DocumentEvidencePage(listOf(leaveEvidence())))
    }

    init {
        base.workspaceState.value =
            AppResult.Success(
                base.workspace.copy(
                    permissions = base.workspace.permissions + "documents.self.read"
                )
            )
    }

    val remote =
        object : DocumentRepository {
            override suspend fun record(
                access: DocumentAccess,
                id: String,
            ): AppResult<
                dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogRecord
            > = error("This evidence selector uses its own live review")

            override suspend fun employments(
                access: DocumentAccess,
                after: String?,
            ): AppResult<DocumentEmploymentPage> =
                error("Evidence selection must not acquire upload employment references")

            override suspend fun evidence(
                access: DocumentAccess,
                query: DocumentEvidenceQuery,
            ): AppResult<DocumentEvidencePage> {
                queries += query
                return read(query)
            }
        }

    fun model(selected: LeaveEvidenceTarget = target) =
        LeaveEvidenceViewModel(
            selected,
            LoadDocumentEvidence(remote, base.sessions, base.workspaces, base.clock, base.build),
            base.clock,
        )
}

fun leaveEvidence(index: Int = 1) =
    DocumentEvidence(
        "44444444-4444-4444-8444-${index.toString().padStart(12, '0')}",
        "55555555-5555-4555-8555-${index.toString().padStart(12, '0')}",
        1,
        "Evidence $index",
        "evidence-$index.pdf",
        "application/pdf",
        100,
        "a".repeat(64),
        Instant.parse("2026-10-12T01:00:00Z"),
    )
