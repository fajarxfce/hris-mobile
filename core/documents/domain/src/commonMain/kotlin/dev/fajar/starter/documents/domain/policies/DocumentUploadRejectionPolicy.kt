package dev.fajar.starter.documents.domain.policies

import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.documents.domain.entities.DocumentUpload
import dev.fajar.starter.documents.domain.entities.DocumentUploadStep

/**
 * Only a known first response can prove this upload never committed. Attempts persist before I/O.
 */
fun isDocumentUploadRejected(upload: DocumentUpload?): Boolean =
    upload?.step is DocumentUploadStep.Start &&
        upload.attempts == 1 &&
        upload.failure?.kind in
            setOf(
                FailureKind.Validation,
                FailureKind.AccessDenied,
                FailureKind.Unauthorized,
                FailureKind.Conflict,
                FailureKind.Unavailable,
            ) &&
        upload.failure?.code in definiteDocumentUploadRejections

private val definiteDocumentUploadRejections =
    setOf(
        "invalid_document_upload",
        "invalid_document_metadata",
        "employee_not_found",
        "document_access_denied",
        "document_identity_mismatch",
        "document_metadata_mismatch",
        "document_revision_limit",
        "document_archived",
        "document_upload_active",
        "stale_version",
        "company_not_found",
        "company_access_denied",
        "session_revoked",
        "mfa_required",
        "mfa_setup_required",
        "mfa_verification_required",
        "recent_authentication_required",
    )
