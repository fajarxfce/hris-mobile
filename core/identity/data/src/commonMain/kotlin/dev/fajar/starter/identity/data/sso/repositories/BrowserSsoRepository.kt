package dev.fajar.starter.identity.data.sso.repositories

import dev.fajar.starter.identity.data.sso.boundary.safeOidcCall
import dev.fajar.starter.identity.data.sso.config.SsoConfiguration
import dev.fajar.starter.identity.data.sso.datasources.*
import dev.fajar.starter.identity.data.sso.mappers.*
import dev.fajar.starter.identity.domain.sso.entities.SsoProvider
import dev.fajar.starter.identity.domain.sso.repositories.SsoRepository
import kotlinx.coroutines.*
import org.koin.core.annotation.Single

@Single
class BrowserSsoRepository(
    private val configuration: SsoConfiguration,
    private val protocol: OidcProtocolSource,
    private val browser: BrowserAuthorizationSource,
) : SsoRepository {
    override suspend fun providers() = safeOidcCall {
        configuration.clients
            .filter { configuration.platform in it.platforms }
            .map { SsoProvider(it.id, it.label) }
    }

    override suspend fun authorize(provider: SsoProvider) =
        withContext(Dispatchers.Main.immediate) {
            safeOidcCall {
                val client = configuration.clients.first { it.id == provider.id }
                val registration = client.platforms.getValue(configuration.platform)
                browser.open(registration.redirectUri).use { session ->
                    val request = protocol.prepare(client.issuer, registration)
                    currentCoroutineContext().ensureActive()
                    validateOidcRequest(request, client.issuer)
                    session
                        .authorize(request)
                        .toSsoProof(provider.id, configuration.platform, request)
                }
            }
        }
}
