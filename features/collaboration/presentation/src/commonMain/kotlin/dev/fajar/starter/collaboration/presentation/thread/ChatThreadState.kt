package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.replies.ChatUnsentReply
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
    val unsentReplies: List<ChatUnsentReply> = emptyList(),
    val unsentFailure: Failure? = null,
    val draft: String = "",
    val editing: ChatEditState? = null,
    val editFailure: Failure? = null,
    val editAcknowledging: Boolean = false,
    val draftReplyToEventId: String? = null,
    val quotes: List<CollaborationQuotedMessage> = emptyList(),
    val selfSender: String? = null,
    val draftReady: Boolean = false,
    val draftSaving: Boolean = false,
    val draftFailure: Failure? = null,
    val draftDialog: ChatDraftDialog? = null,
    val cancellationDialog: ChatCancellationDialog? = null,
    val selecting: Boolean = false,
    val selectionMenuOpen: Boolean = false,
    val selectedMessages: List<ChatMessageRow> = emptyList(),
    /** Captured at the selection gesture; final authoring eligibility is rechecked locally. */
    val selectionTime: Long = 0,
    val deletionDialog: ChatDeletionDialog? = null,
    val forwarding: ChatForwardState? = null,
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
    private val composerReady: Boolean
        get() =
            title != null &&
                draftReady &&
                !leaving &&
                draftDialog == null &&
                cancellationDialog == null &&
                !selecting &&
                forwarding == null &&
                deletionDialog == null &&
                localFailure == null &&
                !saving

    val canCompose: Boolean
        get() = composerReady && !submissionPending && editing == null

    val canEditInput: Boolean
        get() = composerReady && !submissionPending && editing != null

    val canSend: Boolean
        get() = composerReady && editing == null && (submissionPending || draft.isNotBlank())

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
