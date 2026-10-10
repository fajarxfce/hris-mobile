package dev.fajar.starter.identity.data.sso.datasources

import io.ktor.http.Url
import kotlinx.browser.window

/** Reserve the popup before discovery suspends, while the click still has user activation. */
class WebBrowserAuthorizationSource : BrowserAuthorizationSource {
    private var active: WebBrowserAuthorizationSession? = null

    override suspend fun open(redirectUri: String): BrowserAuthorizationSession {
        check(active == null) { "An authorization window is already active." }
        val redirect = Url(redirectUri)
        val origin =
            redirect.protocol.name +
                "://" +
                redirect.host +
                if (redirect.port == redirect.protocol.defaultPort) "" else ":${redirect.port}"
        require(origin == window.location.origin) {
            "The callback must share the application's origin."
        }
        val popup =
            window.open(
                "about:blank",
                "_blank",
                "width=600,height=720,resizable=yes,scrollbars=yes",
            ) ?: throw UnsupportedOperationException("The authorization popup was blocked.")
        return WebBrowserAuthorizationSession(popup, origin, redirectUri) { active = null }
            .also { active = it }
    }
}
