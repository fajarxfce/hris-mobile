package dev.fajar.starter.documents.presentation.detail

import dev.fajar.starter.documents.domain.entities.DocumentCategory
import dev.fajar.starter.documents.domain.entities.DocumentClassification

sealed interface DocumentFileEvent {
    data object Opened : DocumentFileEvent

    data object Closed : DocumentFileEvent

    data object RetryRequested : DocumentFileEvent

    data object FormReloadRequested : DocumentFileEvent

    data class TitleChanged(val value: String) : DocumentFileEvent {
        override fun toString() = "TitleChanged([redacted])"
    }

    data class ReasonChanged(val value: String) : DocumentFileEvent {
        override fun toString() = "ReasonChanged([redacted])"
    }

    data class ClassificationChanged(val value: DocumentClassification) : DocumentFileEvent

    data object CategoryRequested : DocumentFileEvent

    data object CategoryDismissed : DocumentFileEvent

    data class CategorySelected(val value: DocumentCategory) : DocumentFileEvent

    data class DocumentNumberChanged(val value: String) : DocumentFileEvent {
        override fun toString() = "DocumentNumberChanged([redacted])"
    }

    data class IssuingCountryChanged(val value: String) : DocumentFileEvent {
        override fun toString() = "IssuingCountryChanged([redacted])"
    }

    data class IssueDateChanged(val value: String) : DocumentFileEvent {
        override fun toString() = "IssueDateChanged([redacted])"
    }

    data class ValidUntilChanged(val value: String) : DocumentFileEvent {
        override fun toString() = "ValidUntilChanged([redacted])"
    }

    data object EmploymentRequested : DocumentFileEvent

    data object EmploymentDismissed : DocumentFileEvent

    data class EmploymentPageRequested(val after: String?) : DocumentFileEvent

    data class EmploymentSelected(val id: String) : DocumentFileEvent

    data object UploadConfirmed : DocumentFileEvent

    data object ResumeRequested : DocumentFileEvent

    data object StatusRequested : DocumentFileEvent

    data object ValidationRetryRequested : DocumentFileEvent

    data object CancellationRequested : DocumentFileEvent

    data object CancellationDismissed : DocumentFileEvent

    data class CancellationReasonChanged(val value: String) : DocumentFileEvent {
        override fun toString() = "CancellationReasonChanged([redacted])"
    }

    data object CancellationConfirmed : DocumentFileEvent

    data object VerificationRequested : DocumentFileEvent

    data object BackRequested : DocumentFileEvent
}
