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
        return AppLink.entries.singleOrNull { it.path == path }
    }
}
