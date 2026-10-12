package dev.fajar.starter.documents.presentation.download

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.entities.LocalDocumentFile
import dev.fajar.starter.documents.presentation.files.DocumentFileItem

enum class DocumentDownloadWork {
    Downloading,
    Saving,
    Revalidating,
}

data class DocumentDownloadState(
    val file: LocalDocumentFile? = null,
    val item: DocumentFileItem? = null,
    val loading: Boolean = true,
    val work: DocumentDownloadWork? = null,
    val saved: Boolean = false,
    val failure: Failure? = null,
    val restriction: Failure? = null,
) {
    val busy: Boolean
        get() = work != null || loading

    val canDownload: Boolean
        get() = !busy && restriction == null && file != null && file.sha256 == null

    val canSave: Boolean
        get() = !busy && restriction == null && file?.sha256 != null

    val needsVerification: Boolean
        get() =
            restriction?.code in
                setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
}
