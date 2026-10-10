package dev.fajar.starter.identity.data.sso.datasources

import dev.fajar.starter.identity.data.sso.dto.*

/** Raw I/O handle. The repository closes it on success, failure, timeout, and cancellation. */
interface BrowserAuthorizationSession : AutoCloseable {
    suspend fun authorize(request: OidcRequestDto): OidcResponseDto

    override fun close()
}
