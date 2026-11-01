package dev.fajar.starter.collaboration.presentation.thread

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
    val rows: List<ChatMessageRow> = emptyList(),
    val draft: String = "",
    val draftReady: Boolean = false,
    val draftSaving: Boolean = false,
    val draftFailure: Failure? = null,
    val draftDialog: ChatDraftDialog? = null,
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
                draftDialog == null

    val canSend: Boolean
        get() =
            title != null &&
                draftReady &&
                !leaving &&
                draftDialog == null &&
                localFailure == null &&
                !saving &&
                (submissionPending || draft.isNotBlank())

    override fun toString() = "ChatThreadState([redacted])"
}

enum class ChatDraftDialog {
    RELOAD,
    LEAVE,
}
