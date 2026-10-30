package dev.fajar.starter.documents.presentation.detail

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.isDocumentUploadRejected
import dev.fajar.starter.documents.presentation.files.DocumentFileItem

data class DocumentFileState(
    val file: LocalDocumentFile? = null,
    val item: DocumentFileItem? = null,
    val form: DocumentUploadForm? = null,
    val choices: DocumentUploadPreparation? = null,
    val choosingEmployment: Boolean = false,
    val choosingCategory: Boolean = false,
    val loading: Boolean = true,
    val formLoading: Boolean = false,
    val working: Boolean = false,
    val revalidating: Boolean = false,
    val writeAllowed: Boolean = false,
    val cancellationReason: String? = null,
    val failure: Failure? = null,
    val formFailure: Failure? = null,
    val readFailure: Failure? = null,
    val restriction: Failure? = null,
) {
    val busy: Boolean
        get() = working || formLoading || revalidating

    val canWrite: Boolean
        get() = writeAllowed && restriction == null && !busy

    val canPrepareUpload: Boolean
        get() = file != null && (file.upload == null || isDocumentUploadRejected(file.upload))

    val rejectedFailure: Failure?
        get() = file?.upload?.takeIf(::isDocumentUploadRejected)?.failure

    val canSubmit: Boolean
        get() = canWrite && form?.employmentId != null && canPrepareUpload

    val canResume: Boolean
        get() =
            canWrite &&
                !isDocumentUploadRejected(file?.upload) &&
                file?.upload?.step.let {
                    it is DocumentUploadStep.Start ||
                        it is DocumentUploadStep.Chunk ||
                        it is DocumentUploadStep.Validate ||
                        it is DocumentUploadStep.Cancel
                }

    val canCancel: Boolean
        get() =
            canWrite &&
                file?.upload != null &&
                item?.removable == false &&
                file.upload?.step !is DocumentUploadStep.Cancel

    val canCheck: Boolean
        get() = canWrite && file?.upload != null && !isDocumentUploadRejected(file.upload)

    val canRetryValidation: Boolean
        get() =
            canWrite &&
                (file?.upload?.step as? DocumentUploadStep.Finished)?.revision?.status ==
                    DocumentRevisionStatus.VALIDATION_FAILED

    val needsVerification: Boolean
        get() =
            restriction?.code in
                setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
}
