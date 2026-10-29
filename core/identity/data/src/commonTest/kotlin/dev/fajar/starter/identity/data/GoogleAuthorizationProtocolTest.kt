package dev.fajar.starter.identity.data

import dev.fajar.starter.identity.data.firebase.boundary.*
import dev.fajar.starter.identity.data.firebase.mappers.*
import io.ktor.http.*
import kotlin.test.*
import org.publicvalue.multiplatform.oidc.encodeForPKCE
import org.publicvalue.multiplatform.oidc.flows.Pkce
import org.publicvalue.multiplatform.oidc.secureRandomBytes
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod

class GoogleAuthorizationProtocolTest {
    private val clientId = "123-ios.apps.googleusercontent.com"
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    private val request =
        googleAuthorizationRequest(clientId, verifier, challenge, "n".repeat(43), "s".repeat(43))

    @Test
    fun authorizationUsesPinnedEndpointsOnlineScopesAndS256WithoutPublishingTheVerifier() {
        assertEquals(challenge, Pkce(CodeChallengeMethod.S256, verifier).codeChallenge)
        val url = Url(request.authorizationUrl)
        assertEquals(
            "https://accounts.google.com/o/oauth2/v2/auth",
            request.authorizationUrl.substringBefore('?'),
        )
        assertEquals(clientId, url.parameters["client_id"])
        assertEquals(
            "com.googleusercontent.apps.123-ios:/oauthredirect",
            url.parameters["redirect_uri"],
        )
        assertEquals("code", url.parameters["response_type"])
        assertEquals("openid email profile", url.parameters["scope"])
        assertEquals("online", url.parameters["access_type"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals(challenge, url.parameters["code_challenge"])
        assertEquals(request.nonce, url.parameters["nonce"])
        assertEquals(request.state, url.parameters["state"])
        assertFalse(request.authorizationUrl.contains(verifier))
        assertEquals("GoogleAuthorizationRequest(<redacted>)", request.toString())
    }

    @Test
    fun theSdkSuppliesDistinctStrongPkceNonceAndStateForEachAttempt() {
        val first = Pkce(CodeChallengeMethod.S256)
        val second = Pkce(CodeChallengeMethod.S256)
        val nonce = secureRandomBytes().encodeForPKCE()
        val state = secureRandomBytes().encodeForPKCE()
        assertNotEquals(first.codeVerifier, second.codeVerifier)
        assertNotEquals(nonce, state)
        googleAuthorizationRequest(clientId, first.codeVerifier, first.codeChallenge, nonce, state)
    }

    @Test
    fun onlyTheExactRedirectAndCorrelatedSingleCodeBecomeTransientCredentials() {
        val result =
            decodeGoogleAuthorizationCallback(
                "${request.redirectUri}?code=owned%2Bcode&state=${request.state}&scope=openid",
                request,
            )
        assertEquals("owned+code", result.code)
        assertEquals(verifier, result.verifier)
        assertEquals(request.nonce, result.nonce)
        assertEquals(request.redirectUri, result.redirectUri)
        assertEquals("GoogleCredentialResult.AuthorizationCode(<redacted>)", result.toString())
        for (callback in
            listOf(
                "${request.redirectUri}?code=owned&state=another-attempt",
                "${request.redirectUri}?code=owned&state=${request.state}&state=${request.state}",
                "${request.redirectUri}?code=owned&%63ode=second&state=${request.state}",
                "${request.redirectUri}?code=owned&error=access_denied&state=${request.state}",
                "${request.redirectUri}?code=owned&state=${request.state}#ignored",
                "${request.redirectUri}/other?code=owned&state=${request.state}",
                "${request.redirectUri.replace(":/", "://host/")}?code=owned&state=${request.state}",
                "${request.redirectUri}?code=&state=${request.state}",
                "${request.redirectUri}?code=%0Aprivate&state=${request.state}",
                "${request.redirectUri}?state=${request.state}",
                "${request.redirectUri}?code=${"x".repeat(8193)}&state=${request.state}",
            )) assertFailsWith<IllegalArgumentException> {
            decodeGoogleAuthorizationCallback(callback, request)
        }
    }

    @Test
    fun aCorrelatedProviderDismissalIsDistinctFromAnUntrustedCallbackOrServerFailure() {
        assertFailsWith<GoogleCredentialCancelled> {
            decodeGoogleAuthorizationCallback(
                "${request.redirectUri}?error=access_denied&state=${request.state}",
                request,
            )
        }
        assertFailsWith<GoogleCredentialUnavailable> {
            decodeGoogleAuthorizationCallback(
                "${request.redirectUri}?error=server_error&error_description=private&state=${request.state}",
                request,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            decodeGoogleAuthorizationCallback(
                "${request.redirectUri}?error=access_denied&state=untrusted",
                request,
            )
        }
    }

    @Test
    fun malformedClientOrPkceNeverOpensTheAuthorizationEndpoint() {
        for (value in
            listOf(
                "",
                "123-ios.apps.googleusercontent.com.evil",
                "../client",
                "1-${"a".repeat(300)}.apps.googleusercontent.com",
            )) assertFailsWith<IllegalArgumentException> { googleRedirectUri(value) }
        assertFailsWith<IllegalArgumentException> {
            googleAuthorizationRequest(clientId, "short", challenge, request.nonce, request.state)
        }
        assertFailsWith<IllegalArgumentException> {
            googleAuthorizationRequest(clientId, verifier, "plain", request.nonce, request.state)
        }
    }
}
