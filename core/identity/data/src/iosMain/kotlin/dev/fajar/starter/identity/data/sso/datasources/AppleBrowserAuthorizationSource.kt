package dev.fajar.starter.identity.data.sso.datasources

import platform.UIKit.*

class AppleBrowserAuthorizationSource : BrowserAuthorizationSource {
    private var active: AppleBrowserAuthorizationSession? = null

    override suspend fun open(redirectUri: String): BrowserAuthorizationSession {
        check(active == null) { "An authorization resource is already active." }
        val window =
            UIApplication.sharedApplication.connectedScenes
                .filterIsInstance<UIWindowScene>()
                .flatMap { it.windows.filterIsInstance<UIWindow>() }
                .firstOrNull { it.isKeyWindow() }
        return AppleBrowserAuthorizationSession(
                checkNotNull(window) { "No authorization window is active." },
                redirectUri,
            ) {
                active = null
            }
            .also { active = it }
    }
}
