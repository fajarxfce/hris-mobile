package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.firebase.boundary.*
import dev.fajar.starter.identity.data.firebase.dto.GoogleAuthorizationRequest
import dev.fajar.starter.identity.data.firebase.mappers.decodeGoogleAuthorizationCallback
import dev.fajar.starter.identity.data.sso.datasources.AppleAuthorizationAnchor
import platform.AuthenticationServices.*
import platform.Foundation.*
import platform.UIKit.*

internal fun openAppleGoogleBrowser(
    request: GoogleAuthorizationRequest,
    callback: GoogleCredentialCallback,
): AutoCloseable {
    val window =
        UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { it.windows.filterIsInstance<UIWindow>() }
            .firstOrNull { it.isKeyWindow() } ?: throw GoogleCredentialUnavailable()
    val resource = AppleGoogleBrowser(window, request, callback)
    return try {
        resource.start()
        resource
    } catch (error: Exception) {
        resource.close()
        throw error
    }
}

private class AppleGoogleBrowser(
    window: UIWindow,
    request: GoogleAuthorizationRequest,
    callback: GoogleCredentialCallback,
) : AutoCloseable {
    private var closed = false
    private var anchor: AppleAuthorizationAnchor? = AppleAuthorizationAnchor(window)
    private var browser: ASWebAuthenticationSession? =
        ASWebAuthenticationSession(
                uRL = checkNotNull(NSURL.URLWithString(request.authorizationUrl)),
                callbackURLScheme = request.redirectUri.substringBefore(':'),
                completionHandler = { url, error ->
                    NSOperationQueue.mainQueue.addOperationWithBlock {
                        if (!closed) {
                            when {
                                url != null ->
                                    try {
                                        callback.success(
                                            decodeGoogleAuthorizationCallback(
                                                url.absoluteString.orEmpty(),
                                                request,
                                            )
                                        )
                                    } catch (failure: Exception) {
                                        callback.failure(failure)
                                    }
                                error == null ||
                                    (error.domain == ASWebAuthenticationSessionErrorDomain &&
                                        error.code ==
                                            ASWebAuthenticationSessionErrorCodeCanceledLogin) ->
                                    callback.failure(GoogleCredentialCancelled())
                                else -> callback.failure(GoogleCredentialUnavailable())
                            }
                        }
                    }
                },
            )
            .apply {
                presentationContextProvider = anchor
                prefersEphemeralWebBrowserSession = false
            }

    fun start() {
        if (browser?.start() != true) throw GoogleCredentialUnavailable()
    }

    override fun close() {
        check(NSThread.isMainThread)
        if (closed) return
        closed = true
        browser?.cancel()
        browser = null
        anchor = null
    }
}
