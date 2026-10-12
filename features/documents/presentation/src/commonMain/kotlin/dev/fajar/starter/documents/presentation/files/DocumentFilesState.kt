package dev.fajar.starter.documents.presentation.files

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes

data class DocumentFilesState(
    val content: DocumentLocalFiles? = null,
    val items: List<DocumentFileItem> = emptyList(),
    val loading: Boolean = true,
    val importing: Boolean = false,
    val removing: Boolean = false,
    val revalidating: Boolean = false,
    val removal: LocalDocumentFile? = null,
    val failure: Failure? = null,
    val readFailure: Failure? = null,
    val restriction: Failure? = null,
) {
    val busy: Boolean
        get() = importing || removing || revalidating

    val canImport: Boolean
        get() =
            !busy &&
                restriction == null &&
                content?.access?.permissions?.contains("documents.self.upload") == true

    val needsVerification: Boolean
        get() =
            restriction?.code in
                setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
}

internal fun documentReadRestricted(failure: Failure): Boolean =
    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
        failure.code in workspaceAccessLossCodes ||
        failure.code in
            setOf(
                "company_module_disabled",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
                "client.company_policy_expired",
                "client.company_policy_unavailable",
            )
