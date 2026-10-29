package dev.fajar.starter.common.config

/** Extracts a bounded path; the consuming application still admits its routes and session. */
class AppLinkPath(
    private val environment: AppEnvironment,
    private val httpsHost: String? = BuildAppLinks.hosts[environment],
) {
    init {
        require(httpsHost == null || validHost(httpsHost))
    }

    operator fun invoke(uri: String): String? {
        // URI admission is ASCII: Unicode case folding must not alias another DNS host.
        if (uri.length > 4096 || uri.any { it.code !in 33..126 }) return null
        val customPrefix = "${environment.linkScheme}://app/"
        val httpsPrefix = httpsHost?.let { "https://$it/" }
        val path =
            when {
                uri.startsWith(customPrefix, ignoreCase = true) ->
                    uri.substring(customPrefix.length)
                uri.startsWith("#/") -> uri.substring(2)
                httpsPrefix != null && uri.startsWith(httpsPrefix, ignoreCase = true) -> {
                    val remaining = uri.substring(httpsPrefix.length)
                    val prefix = "app/${environment.id}/"
                    if (!remaining.startsWith(prefix)) return null
                    remaining.substring(prefix.length)
                }
                else -> return null
            }
        return path.takeIf { PATH.matches(it) }
    }

    private companion object {
        val PATH = Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")
        val LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

        fun validHost(host: String): Boolean {
            if (host.length !in 3..253) return false
            val labels = host.split('.')
            return labels.size >= 2 &&
                labels.all { LABEL.matches(it) } &&
                labels.last().firstOrNull() in 'a'..'z'
        }
    }
}
