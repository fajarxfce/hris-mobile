package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.firebase.dto.GoogleAuthorizationRequest
import dev.fajar.starter.identity.data.firebase.mappers.googleAuthorizationRequest
import org.publicvalue.multiplatform.oidc.encodeForPKCE
import org.publicvalue.multiplatform.oidc.flows.Pkce
import org.publicvalue.multiplatform.oidc.secureRandomBytes
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod
import platform.Foundation.NSThread

/** The application owns the source; each browser attempt owns its window and cancellation. */
class AppleGoogleCredentialSource(
    private val request: (GoogleAuthorizationRequest, GoogleCredentialCallback) -> AutoCloseable =
        ::openAppleGoogleBrowser
) : GoogleCredentialSource {
    private var active: GoogleCredentialSession? = null
    private var closed = false
    override val supported = true

    override suspend fun open(clientId: String): GoogleCredentialSession {
        check(NSThread.isMainThread)
        check(!closed && active == null)
        val pkce = Pkce(CodeChallengeMethod.S256)
        val attempt =
            googleAuthorizationRequest(
                clientId,
                pkce.codeVerifier,
                pkce.codeChallenge,
                secureRandomBytes().encodeForPKCE(),
                secureRandomBytes().encodeForPKCE(),
            )
        return CallbackGoogleCredentialSession({ request(attempt, it) }, { active = null }).also {
            active = it
        }
    }

    override fun close() {
        check(NSThread.isMainThread)
        if (closed) return
        closed = true
        active?.close()
    }
}
