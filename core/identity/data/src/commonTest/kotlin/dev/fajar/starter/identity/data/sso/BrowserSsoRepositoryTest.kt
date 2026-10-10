@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.data.sso

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.sso.boundary.*
import dev.fajar.starter.identity.data.sso.config.SsoConfiguration
import dev.fajar.starter.identity.data.sso.datasources.*
import dev.fajar.starter.identity.data.sso.dto.*
import dev.fajar.starter.identity.data.sso.mappers.decodeOidcCallback
import dev.fajar.starter.identity.data.sso.repositories.BrowserSsoRepository
import dev.fajar.starter.identity.domain.sso.entities.SsoProvider
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class BrowserSsoRepositoryTest {
    @BeforeTest
    fun before() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun after() {
        Dispatchers.resetMain()
    }

    @Test
    fun invalidStateAndDiscoveryBothCloseTheResourceAndRejectTheProof() = runTest {
        val browser = BrowserFixture { OidcResponseDto("private-code", "wrong-state") }
        val result = repository(ProtocolFixture(), browser).authorize(provider)
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(result).failure.kind)
        assertEquals(1, browser.closed)
        val second = BrowserFixture()
        val failed =
            repository(ProtocolFixture { request.copy(issuer = "https://other.example") }, second)
                .authorize(provider)
        assertIs<AppResult.Failed>(failed)
        assertEquals(0, second.authorizations)
        assertEquals(1, second.closed)
    }

    @Test
    fun cancellationDuringNonCooperativeDiscoveryCannotLaunchTheProvider() = runTest {
        val pending = CompletableDeferred<Unit>()
        val browser = BrowserFixture()
        val protocol = ProtocolFixture {
            withContext(NonCancellable) { pending.await() }
            request
        }
        val job = async { repository(protocol, browser).authorize(provider) }
        runCurrent()
        job.cancel()
        pending.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(0, browser.authorizations)
        assertEquals(1, browser.closed)
    }

    @Test
    fun dismissedWindowIsDistinctFromCoroutineCancellationAndSecretsRemainRedacted() = runTest {
        val browser = BrowserFixture { throw BrowserAuthorizationCancelled() }
        assertEquals(
            FailureKind.Cancelled,
            assertIs<AppResult.Failed>(repository(ProtocolFixture(), browser).authorize(provider))
                .failure
                .kind,
        )
        assertEquals(1, browser.closed)
        val cancelled = CancellationException("private cancellation")
        val thrown =
            assertFailsWith<CancellationException> {
                safeOidcCall { throw IllegalStateException("SDK wrapper", cancelled) }
            }
        assertSame(cancelled, thrown)
        val success =
            assertIs<AppResult.Success<*>>(
                repository(ProtocolFixture(), BrowserFixture()).authorize(provider)
            )
        assertFalse(success.toString().contains("private-code"))
        assertFalse(request.toString().contains(request.codeVerifier))
    }

    @Test
    fun callbackParserRejectsAnotherRedirectOrAmbiguousParameters() {
        assertFailsWith<OidcProtocolException> {
            decodeOidcCallback("https://app.example/other?code=1&state=s", redirect)
        }
        assertFailsWith<OidcProtocolException> {
            decodeOidcCallback("$redirect?code=1&code=2&state=s", redirect)
        }
        assertFailsWith<OidcProtocolException> {
            decodeOidcCallback("https://other.example/oauth/callback?code=1&state=s", redirect)
        }
    }
}

private const val redirect = "https://app.example/oauth/callback"
private val provider = SsoProvider("organization", "Organization")
private val request =
    OidcRequestDto(
        "https://idp.example",
        "https://idp.example/authorize",
        "https://idp.example/token",
        "https://idp.example/authorize?state=s",
        "client",
        redirect,
        "expected-state",
        "nonce",
        "v".repeat(43),
    )

private fun repository(protocol: OidcProtocolSource, browser: BrowserAuthorizationSource) =
    BrowserSsoRepository(
        SsoConfiguration(
            listOf(
                OidcClientSettings(
                    provider.id,
                    provider.label,
                    request.issuer,
                    mapOf(AppPlatform.Web to OidcPlatformSettings("client", redirect)),
                )
            ),
            AppPlatform.Web,
        ),
        protocol,
        browser,
    )

private class ProtocolFixture(private val operation: suspend () -> OidcRequestDto = { request }) :
    OidcProtocolSource {
    override suspend fun prepare(issuer: String, settings: OidcPlatformSettings) = operation()
}

private class BrowserFixture(
    private val operation: suspend () -> OidcResponseDto = {
        OidcResponseDto("private-code", "expected-state")
    }
) : BrowserAuthorizationSource {
    var closed = 0
    var authorizations = 0

    override suspend fun open(redirectUri: String) =
        object : BrowserAuthorizationSession {
            override suspend fun authorize(request: OidcRequestDto): OidcResponseDto {
                authorizations++
                return operation()
            }

            override fun close() {
                closed++
            }
        }
}
