package dev.fajar.starter.identity.data.sso.datasources
class UnavailableBrowserAuthorizationSource : BrowserAuthorizationSource {
    override suspend fun open(redirectUri: String): BrowserAuthorizationSession =
        throw UnsupportedOperationException("No authorization host is attached.")
}
