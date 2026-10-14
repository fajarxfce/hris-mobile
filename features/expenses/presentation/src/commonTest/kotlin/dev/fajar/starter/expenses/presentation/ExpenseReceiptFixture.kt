package dev.fajar.starter.expenses.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.repositories.DocumentRepository
import dev.fajar.starter.documents.domain.usecases.LoadDocumentEvidence
import dev.fajar.starter.expenses.presentation.receipts.*
import kotlin.time.Instant

class ExpenseReceiptFixture(instant: () -> Instant) {
    val base = ExpenseFixture(instant)
    val target = ExpenseReceiptTarget(base.workspace.id, base.employee.id, emptySet())
    val queries = mutableListOf<DocumentEvidenceQuery>()
    var read: suspend (DocumentEvidenceQuery) -> AppResult<DocumentEvidencePage> = {
        AppResult.Success(DocumentEvidencePage(listOf(expenseReceipt())))
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

    fun model(selected: ExpenseReceiptTarget = target) =
        ExpenseReceiptViewModel(
            selected,
            LoadDocumentEvidence(remote, base.sessions, base.workspaces, base.clock, base.build),
            base.clock,
        )
}

fun expenseReceipt(index: Int = 1) =
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
