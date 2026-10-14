package dev.fajar.starter.documents.presentation.catalog

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.entities.DocumentEmployment
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogSelection
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogSnapshot

data class DocumentCatalogState(
    val employments: List<DocumentEmployment>? = null,
    val selection: DocumentCatalogSelection = DocumentCatalogSelection(),
    val catalog: DocumentCatalogSnapshot? = null,
    val choosingEmployment: Boolean = false,
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val preparingId: String? = null,
    val failure: Failure? = null,
    val restriction: Failure? = null,
) {
    val employment: DocumentEmployment?
        get() = employments?.firstOrNull { it.id == selection.employmentId }

    val busy: Boolean
        get() = loading || preparingId != null

    val canChoose: Boolean
        get() = !busy && restriction == null

    val needsVerification: Boolean
        get() =
            (restriction ?: failure)?.code in
                setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
}
