package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalAccess

sealed interface ChatThreadEvent {
    data class UnsentReplySelected(val id: String) : ChatThreadEvent

    data object LocalReadRetryRequested : ChatThreadEvent

    /** A navigation hint starts private reads earlier; domain publication still verifies access. */
    data class Entered(val accessHint: CollaborationLocalAccess? = null) : ChatThreadEvent

    /**
     * Reads owned local content while the destination is still visible in the conversation list.
     */
    data class LocalPrepared(val accessHint: CollaborationLocalAccess) : ChatThreadEvent

    data object Stopped : ChatThreadEvent

    data object RouteReleased : ChatThreadEvent

    data class ViewportChanged(val token: String?, val rowKeys: List<String>) : ChatThreadEvent {
        override fun toString() = "ViewportChanged([redacted])"
    }

    data object BackRequested : ChatThreadEvent

    data object SetupRequested : ChatThreadEvent

    data object RefreshRequested : ChatThreadEvent

    data object OlderRequested : ChatThreadEvent

    data object NewerRequested : ChatThreadEvent

    data object LatestRequested : ChatThreadEvent

    data object SendRequested : ChatThreadEvent

    data object DraftRetryRequested : ChatThreadEvent

    data object DraftDialogDismissed : ChatThreadEvent

    data object DraftDialogConfirmed : ChatThreadEvent

    data class CancelRequested(val id: String) : ChatThreadEvent {
        override fun toString() = "CancelRequested([redacted])"
    }

    data object CancellationConfirmed : ChatThreadEvent

    data object CancellationDismissed : ChatThreadEvent

    data class MessageSelected(val key: String) : ChatThreadEvent {
        override fun toString() = "MessageSelected([redacted])"
    }

    data object SelectionCleared : ChatThreadEvent

    data object SelectionMenuOpened : ChatThreadEvent

    data object SelectionMenuDismissed : ChatThreadEvent

    data object ReplySelected : ChatThreadEvent

    data object ReplyCleared : ChatThreadEvent

    data object EditSelected : ChatThreadEvent

    data object EditCancelled : ChatThreadEvent

    data object EditSaveRequested : ChatThreadEvent

    data object EditFailureDismissed : ChatThreadEvent

    data object FailedEditsDismissRequested : ChatThreadEvent

    data object CopyFailedEdits : ChatThreadEvent

    data class EditChanged(val text: String) : ChatThreadEvent {
        override fun toString() = "EditChanged([redacted])"
    }

    data object CopySelected : ChatThreadEvent

    data object ForwardSelected : ChatThreadEvent

    data object ForwardDismissed : ChatThreadEvent

    data object ForwardConfirmed : ChatThreadEvent

    data object ForwardTargetsRetryRequested : ChatThreadEvent

    data class ForwardSearchChanged(val query: String) : ChatThreadEvent {
        override fun toString() = "ForwardSearchChanged([redacted])"
    }

    data class ForwardTargetSelected(val id: String) : ChatThreadEvent {
        override fun toString() = "ForwardTargetSelected([redacted])"
    }

    data object DeleteSelected : ChatThreadEvent

    data object DeletionConfirmed : ChatThreadEvent

    data object DeletionDismissed : ChatThreadEvent

    data class DraftChanged(val text: String) : ChatThreadEvent {
        override fun toString() = "DraftChanged([redacted])"
    }
}
