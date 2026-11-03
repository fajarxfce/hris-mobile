package dev.fajar.starter.app.navigation

sealed interface AppLink {
    data object Overview : AppLink

    data object Account : AppLink

    data object Inbox : AppLink

    data object DataSync : AppLink

    data object PendingActions : AppLink

    /** Created only after the application authenticates a native notification action. */
    data class ChatThread(
        val owner: dev.fajar.starter.workspace.domain.entities.WorkspaceSelectionOwner,
        val companyId: String,
        val conversationId: String,
    ) : AppLink {
        override fun toString() = "ChatThread([redacted])"
    }

    /** A hint selects a resource; the owning workflow still authorizes every read. */
    data class InboxItem(val accountId: String, val companyId: String, val inboxId: String) :
        AppLink {
        override fun toString() = "InboxItem([redacted])"
    }
}
