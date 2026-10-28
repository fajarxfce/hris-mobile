package dev.fajar.starter.app.navigation

import dev.fajar.starter.common.config.AppEnvironment

/** Application navigation policy. External input can only select known destinations. */
class ResolveAppLink(private val environment: AppEnvironment) {
    operator fun invoke(uri: String): AppLink? {
        if (uri.length > 4096) return null
        val prefix = "${environment.linkScheme}://app/"
        val path =
            when {
                uri.startsWith(prefix, ignoreCase = true) -> uri.substring(prefix.length)
                uri.startsWith("#/") -> uri.substring(2)
                else -> return null
            }
        return when (path) {
            "overview" -> AppLink.Overview
            "account" -> AppLink.Account
            "inbox" -> AppLink.Inbox
            "data-sync" -> AppLink.DataSync
            "pending-actions" -> AppLink.PendingActions
            else -> {
                val parts = path.split('/')
                if (
                    parts.size != 6 ||
                        parts[0] != "accounts" ||
                        parts[2] != "companies" ||
                        parts[4] != "inbox"
                )
                    return null
                val ids = listOf(parts[1], parts[3], parts[5])
                if (ids.any { !UUID.matches(it) }) return null
                AppLink.InboxItem(parts[1], parts[3], parts[5])
            }
        }
    }

    private companion object {
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
