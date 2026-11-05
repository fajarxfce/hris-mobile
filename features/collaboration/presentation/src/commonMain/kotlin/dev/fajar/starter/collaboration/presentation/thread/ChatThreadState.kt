package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.common.result.Failure

enum class ChatThreadConnection {
    OFFLINE,
    CONNECTING,
    SETUP_REQUIRED,
    WAITING,
    CONNECTED,
}

data class ChatThreadState(
    val title: String? = null,
    /** Captured by admitted local context; a route ID alone cannot claim notification focus. */
    val notificationOwner: CollaborationOwner? = null,
    /** Ephemeral owner generation for viewport callbacks; never a credential. */
    val readViewportToken: String? = null,
    val rows: List<ChatMessageRow> = emptyList(),
    val draft: String = "",
    val draftReady: Boolean = false,
    val draftSaving: Boolean = false,
    val draftFailure: Failure? = null,
    val draftDialog: ChatDraftDialog? = null,
    val cancellationDialog: ChatCancellationDialog? = null,
    val selecting: Boolean = false,
    val selectedMessages: List<ChatMessageRow> = emptyList(),
    val deletionDialog: ChatDeletionDialog? = null,
    val selectionLimitReached: Boolean = false,
    val leaving: Boolean = false,
    val submissionPending: Boolean = false,
    val saving: Boolean = false,
    val loading: Boolean = true,
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
    val browsingHistory: Boolean = false,
    val loadingOlder: Boolean = false,
    val historyIncomplete: Boolean = false,
    val historyFailure: Failure? = null,
    val connection: ChatThreadConnection = ChatThreadConnection.OFFLINE,
    val syncing: Boolean = false,
    val localFailure: Failure? = null,
    val failure: Failure? = null,
) {
    val canCompose: Boolean
        get() =
            title != null &&
                draftReady &&
                localFailure == null &&
                !submissionPending &&
                !saving &&
                !leaving &&
                cancellationDialog == null &&
                !selecting &&
                deletionDialog == null &&
                draftDialog == null

    val canSend: Boolean
        get() =
            title != null &&
                draftReady &&
                !leaving &&
                draftDialog == null &&
                cancellationDialog == null &&
                !selecting &&
                deletionDialog == null &&
                localFailure == null &&
                !saving &&
                (submissionPending || draft.isNotBlank())

    override fun toString() = "ChatThreadState([redacted])"
}

data class ChatDeletionDialog(
    val count: Int,
    val busy: Boolean = false,
    val failure: Failure? = null,
)

data class ChatCancellationDialog(
    val text: String,
    val busy: Boolean = false,
    val failure: Failure? = null,
    val unavailable: Boolean = false,
) {
    override fun toString() = "ChatCancellationDialog([redacted])"
}

enum class ChatDraftDialog {
    RELOAD,
    LEAVE,
}
