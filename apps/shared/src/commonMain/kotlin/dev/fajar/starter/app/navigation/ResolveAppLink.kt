package dev.fajar.starter.app.navigation

import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppLinkPath
import dev.fajar.starter.common.config.BuildAppLinks

/** Application navigation policy. External input can only select known destinations. */
class ResolveAppLink(
    environment: AppEnvironment,
    httpsHost: String? = BuildAppLinks.hosts[environment],
) {
    private val path = AppLinkPath(environment, httpsHost)

    operator fun invoke(uri: String): AppLink? {
        val path = path(uri) ?: return null
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
