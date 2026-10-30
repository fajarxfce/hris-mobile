package dev.fajar.starter.documents.presentation.editor

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataView

data class DocumentMetadataEditorTarget(val workspaceId: String, val documentId: String)

data class DocumentMetadataEditorState(
    val active: Boolean = false,
    val access: DocumentAccess? = null,
    val view: DocumentMetadataView? = null,
    val persistence: DocumentMetadataEditPersistence? = null,
    val command: DocumentMetadataCommandRecord? = null,
    val commandsLoaded: Boolean = false,
    val copiesLoaded: Boolean = false,
    val refreshing: Boolean = false,
    val initializing: Boolean = false,
    val work: DocumentMetadataEditorWork = DocumentMetadataEditorWork.NONE,
    val dialog: DocumentMetadataEditorDialog? = null,
    val choosingCategory: Boolean = false,
    val failure: Failure? = null,
    val restriction: Failure? = null,
    val closing: Boolean = false,
) {
    val ready
        get() = active && commandsLoaded && copiesLoaded && restriction == null && !closing

    val idle
        get() = ready && !initializing && work == DocumentMetadataEditorWork.NONE && dialog == null

    val mayWrite
        get() = access?.permissions?.contains("documents.self.upload") == true

    val canEdit
        get() = idle && mayWrite && command == null && persistence != null

    val stale
        get() =
            persistence?.stored?.head?.let { old ->
                view?.snapshot?.checkpoint?.head?.let { it != old }
            } == true

    val canConfirm
        get() =
            canEdit &&
                !stale &&
                view?.snapshot?.checkpoint != null &&
                !refreshing &&
                !choosingCategory &&
                (persistence?.stored?.head?.metadataVersion ?: 999) < 999

    val canReview
        get(): Boolean {
            if (!idle) return false
            val outcome = command?.outcome ?: return false
            if (outcome is DocumentMetadataCommandOutcome.Unconfirmed) return false
            if (outcome !is DocumentMetadataCommandOutcome.Confirmed) return true
            val head = view?.snapshot?.checkpoint?.head ?: return false
            return !refreshing &&
                head.metadataSupported &&
                head.metadata != null &&
                head.version >= outcome.version &&
                head.employmentId == command.command.employmentId
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

enum class DocumentMetadataEditorWork {
    NONE,
    CONFIRMING,
    DELIVERING,
    REVIEWING,
    DISCARDING,
    LEAVING,
}

enum class DocumentMetadataEditorDialog {
    CONFIRM,
    CANCEL_COMMAND,
    DISCARD,
    RESTART,
    RELOAD,
    LEAVE_UNSAVED,
}
