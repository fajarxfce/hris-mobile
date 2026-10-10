package dev.fajar.starter.identity.data.sso.datasources

class DesktopBrowserAuthorizationSource : BrowserAuthorizationSource {
    private var active: DesktopBrowserAuthorizationSession? = null

    override suspend fun open(redirectUri: String): BrowserAuthorizationSession {
        check(active == null) { "An authorization resource is already active." }
        return DesktopBrowserAuthorizationSession(redirectUri) { active = null }
            .also { active = it }
    }
}
