@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.data.sso.datasources

import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import dev.fajar.starter.identity.data.sso.boundary.BrowserAuthorizationCancelled
import dev.fajar.starter.identity.data.sso.dto.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import net.openid.appauth.*

/** Per-request registration avoids delivering a late result to a later sign-in attempt. */
internal class AndroidBrowserAuthorizationSession(
    private val service: AuthorizationService,
    registry: ActivityResultRegistry,
    private val onClosed: () -> Unit,
) : BrowserAuthorizationSession {
    private var closed = false
    private var started = false
    private val response = CompletableDeferred<OidcResponseDto>()
    private val launcher =
        registry.register(
            "oidc:" + Uuid.random(),
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val intent = result.data
            val authorization = intent?.let(AuthorizationResponse::fromIntent)
            val failure = intent?.let(AuthorizationException::fromIntent)
            when {
                authorization != null ->
                    response.complete(
                        OidcResponseDto(authorization.authorizationCode, authorization.state)
                    )
                failure == null ||
                    (failure.type ==
                        AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.type &&
                        failure.code ==
                            AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) ->
                    response.completeExceptionally(BrowserAuthorizationCancelled())
                else -> response.completeExceptionally(failure)
            }
        }

    override suspend fun authorize(request: OidcRequestDto): OidcResponseDto {
        check(!closed && !started) { "The authorization resource cannot be reused." }
        started = true
        val configuration =
            AuthorizationServiceConfiguration(
                Uri.parse(request.authorizationEndpoint),
                Uri.parse(request.tokenEndpoint),
            )
        val authorization =
            AuthorizationRequest.Builder(
                    configuration,
                    request.clientId,
                    ResponseTypeValues.CODE,
                    Uri.parse(request.redirectUri),
                )
                .setScope("openid profile email")
                .setResponseMode("query")
                .setState(request.state)
                .setNonce(request.nonce)
                .setCodeVerifier(request.codeVerifier)
                .setPrompt("login")
                .setAdditionalParameters(mapOf("max_age" to "0"))
                .build()
        launcher.launch(service.getAuthorizationRequestIntent(authorization))
        return response.await()
    }

    override fun close() {
        if (closed) return
        closed = true
        response.cancel()
        try {
            launcher.unregister()
        } finally {
            try {
                service.dispose()
            } finally {
                onClosed()
            }
        }
    }
}
