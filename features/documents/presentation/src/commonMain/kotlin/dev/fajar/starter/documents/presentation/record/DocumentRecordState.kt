package dev.fajar.starter.documents.presentation.record

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataView

data class DocumentRecordState(
    val view: DocumentMetadataView? = null,
    val after: Long? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val failure: Failure? = null,
    val restriction: Failure? = null,
) {
    val canEdit
        get() =
            canPage &&
                view?.access?.permissions?.contains("documents.self.upload") == true &&
                view.snapshot.checkpoint?.head?.let {
                    it.metadataSupported && it.metadata != null && (it.metadataVersion ?: 999) < 999
                } == true

    val canPage
        get() = !refreshing && !loading && restriction == null && view != null

    val needsVerification
        get() =
            restriction?.code in
                setOf(
                    "mfa_required",
                    "mfa_setup_required",
                    "mfa_verification_required",
                    "recent_authentication_required",
                )
}
