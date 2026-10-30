package dev.fajar.starter.documents.presentation.changes

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.metadata.commands.entities.DocumentMetadataCommandsView
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingView

data class DocumentMetadataChangesState(
    val commands: DocumentMetadataCommandsView? = null,
    val copies: DocumentMetadataWorkingView? = null,
    val after: String? = null,
    val loadingCommands: Boolean = true,
    val loadingCopies: Boolean = true,
    val refreshing: Boolean = false,
    val failure: Failure? = null,
    val restriction: Failure? = null,
) {
    // Either observer can carry the latest resource denial. Do not expose the other
    // projection while that reader is unavailable or being replaced.
    private val ready
        get() =
            commands != null &&
                copies != null &&
                !loadingCommands &&
                !loadingCopies &&
                restriction == null

    val unavailable
        get() =
            (commands?.restrictions.orEmpty() + copies?.restrictions.orEmpty()).distinctBy {
                it.documentId
            }

    val records
        get() =
            if (!ready) emptyList()
            else
                unavailable
                    .map { it.documentId }
                    .toSet()
                    .let { hidden ->
                        commands?.records.orEmpty().filter {
                            it.command.change.documentId !in hidden
                        }
                    }

    val drafts
        get() =
            if (!ready) emptyList()
            else
                (unavailable.map { it.documentId } +
                        commands?.records.orEmpty().map { it.command.change.documentId })
                    .toSet()
                    .let { hidden ->
                        copies?.page?.items.orEmpty().filter { it.head.id !in hidden }
                    }

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
