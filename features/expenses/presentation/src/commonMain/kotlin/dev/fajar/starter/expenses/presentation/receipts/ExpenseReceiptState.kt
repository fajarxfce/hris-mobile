package dev.fajar.starter.expenses.presentation.receipts

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.entities.DocumentEvidenceSnapshot

data class ExpenseReceiptState(
    val selectedDocumentIds: Set<String>,
    val snapshot: DocumentEvidenceSnapshot? = null,
    val after: String? = null,
    val loading: Boolean = false,
    val closing: Boolean = false,
    val failure: Failure? = null,
    val restriction: Failure? = null,
) {
    val visibleFailure: Failure?
        get() = restriction ?: failure

    val needsVerification: Boolean
        get() =
            visibleFailure?.code in
                setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
}
