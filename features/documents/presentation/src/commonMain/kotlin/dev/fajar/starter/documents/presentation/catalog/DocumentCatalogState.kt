package dev.fajar.starter.documents.presentation.catalog

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.entities.*

data class DocumentCatalogState(
    val directory: DocumentEmploymentSnapshot? = null,
    val employment: DocumentEmployment? = null,
    val classification: DocumentClassification = DocumentClassification.PERSONAL,
    val catalog: DocumentEvidenceSnapshot? = null,
    val choosingEmployment: Boolean = false,
    val loading: Boolean = true,
    val preparingId: String? = null,
    val expired: Boolean = false,
    val failure: Failure? = null,
    val restriction: Failure? = null,
) {
    val busy: Boolean
        get() = loading || preparingId != null

    val canChoose: Boolean
        get() = !busy && restriction == null

    val needsVerification: Boolean
        get() =
            restriction?.code in
                setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
}
