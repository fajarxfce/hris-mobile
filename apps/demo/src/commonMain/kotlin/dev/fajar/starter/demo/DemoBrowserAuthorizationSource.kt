@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.demo

import dev.fajar.starter.identity.data.sso.datasources.*
import dev.fajar.starter.identity.data.sso.dto.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.delay

/** An explicit in-process provider fixture. It never launches or impersonates an external IdP. */
class DemoBrowserAuthorizationSource : BrowserAuthorizationSource {
    override suspend fun open(redirectUri: String): BrowserAuthorizationSession =
        object : BrowserAuthorizationSession {
            private var closed = false

            override suspend fun authorize(request: OidcRequestDto): OidcResponseDto {
                check(!closed)
                delay(150)
                check(!closed)
                return OidcResponseDto("demo-sso:" + Uuid.random(), request.state)
            }

            override fun close() {
                closed = true
            }
        }
}
