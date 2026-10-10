@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.identity.data.sso.datasources

import dev.fajar.starter.identity.data.sso.boundary.BrowserAuthorizationCancelled
import dev.fajar.starter.identity.data.sso.dto.*
import dev.fajar.starter.identity.data.sso.mappers.decodeOidcCallback
import io.ktor.http.Url
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.*
import platform.Foundation.NSURL
import platform.UIKit.UIWindow

internal class AppleBrowserAuthorizationSession(
    window: UIWindow,
    private val redirectUri: String,
    private val onClosed: () -> Unit,
) : BrowserAuthorizationSession {
    private var anchor: AppleAuthorizationAnchor? = AppleAuthorizationAnchor(window)
    private var browser: ASWebAuthenticationSession? = null
    private var closed = false

    override suspend fun authorize(request: OidcRequestDto): OidcResponseDto {
        check(!closed && browser == null) { "The authorization resource cannot be reused." }
        val callback =
            suspendCancellableCoroutine<String> { continuation ->
                val session =
                    ASWebAuthenticationSession(
                        uRL = checkNotNull(NSURL.URLWithString(request.authorizationUrl)),
                        callbackURLScheme = Url(redirectUri).protocol.name,
                        completionHandler = { url, error ->
                            if (continuation.isActive) {
                                when {
                                    url != null -> continuation.resume(url.absoluteString.orEmpty())
                                    error == null ||
                                        (error.domain == ASWebAuthenticationSessionErrorDomain &&
                                            error.code ==
                                                ASWebAuthenticationSessionErrorCodeCanceledLogin) ->
                                        continuation.resumeWithException(
                                            BrowserAuthorizationCancelled()
                                        )
                                    else ->
                                        continuation.resumeWithException(
                                            AppleAuthorizationException(error)
                                        )
                                }
                            }
                        },
                    )
                browser = session
                session.presentationContextProvider = anchor
                session.prefersEphemeralWebBrowserSession = false
                continuation.invokeOnCancellation {
                    platform.Foundation.NSOperationQueue.mainQueue.addOperationWithBlock {
                        session.cancel()
                    }
                }
                if (!session.start() && continuation.isActive)
                    continuation.resumeWithException(
                        UnsupportedOperationException(
                            "The authorization window could not be opened."
                        )
                    )
            }
        return decodeOidcCallback(callback, redirectUri)
    }

    override fun close() {
        if (closed) return
        closed = true
        browser?.cancel()
        browser = null
        anchor = null
        onClosed()
    }
}
