package dev.fajar.starter.expenses.domain.entities

import dev.fajar.starter.documents.domain.entities.DocumentRevisionStatus

/** Display metadata only; this does not grant access to file contents. */
data class ExpenseReceiptReference(
    val revisionId: String,
    val documentId: String? = null,
    val fileName: String? = null,
    val mediaType: String? = null,
    val size: Long? = null,
    val status: DocumentRevisionStatus? = null,
) {
    override fun toString() = "ExpenseReceiptReference(redacted)"
}
