package dev.fajar.starter.identity.data.sso.datasources
/** Reserves a browser/OS authorization resource before protocol network I/O begins. */
interface BrowserAuthorizationSource {
    suspend fun open(redirectUri: String): BrowserAuthorizationSession
}
