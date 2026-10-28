package dev.fajar.starter.app.navigation

sealed interface AppLink {
    data object Overview : AppLink

    data object Account : AppLink

    data object Inbox : AppLink

    data object DataSync : AppLink

    data object PendingActions : AppLink

    /** A hint selects a resource; the owning workflow still authorizes every read. */
    data class InboxItem(val accountId: String, val companyId: String, val inboxId: String) :
        AppLink {
        override fun toString() = "InboxItem([redacted])"
    }
}
