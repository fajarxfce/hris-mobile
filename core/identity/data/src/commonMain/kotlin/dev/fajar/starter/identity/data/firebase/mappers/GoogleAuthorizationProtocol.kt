package dev.fajar.starter.identity.data.firebase.mappers

import dev.fajar.starter.identity.data.firebase.boundary.*
import dev.fajar.starter.identity.data.firebase.dto.*
import io.ktor.http.*

fun googleRedirectUri(clientId: String): String {
    require(
        clientId.length <= 256 &&
            clientId.matches(Regex("[0-9]+-[a-z0-9]+\\.apps\\.googleusercontent\\.com"))
    )
    return "com.googleusercontent.apps.${clientId.substringBefore(".apps.googleusercontent.com")}:/oauthredirect"
}

fun googleAuthorizationRequest(
    clientId: String,
    verifier: String,
    challenge: String,
    nonce: String,
    state: String,
): GoogleAuthorizationRequest {
    val redirect = googleRedirectUri(clientId)
    require(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
    require(challenge.matches(Regex("[A-Za-z0-9_-]{43}")))
    require(nonce.matches(Regex("[A-Za-z0-9_-]{32,256}")))
    require(state.matches(Regex("[A-Za-z0-9_-]{32,256}")))
    val url =
        URLBuilder("https://accounts.google.com/o/oauth2/v2/auth")
            .apply {
                parameters.append("client_id", clientId)
                parameters.append("redirect_uri", redirect)
                parameters.append("response_type", "code")
                parameters.append("scope", "openid email profile")
                parameters.append("code_challenge_method", "S256")
                parameters.append("code_challenge", challenge)
                parameters.append("nonce", nonce)
                parameters.append("state", state)
                parameters.append("prompt", "select_account")
                parameters.append("access_type", "online")
            }
            .buildString()
    return GoogleAuthorizationRequest(url, redirect, verifier, nonce, state)
}

fun decodeGoogleAuthorizationCallback(
    callback: String,
    request: GoogleAuthorizationRequest,
): GoogleCredentialResult.AuthorizationCode {
    require(callback.length <= 8192 && callback.none { it.code < 32 || it.code == 127 })
    require('#' !in callback && callback.substringBefore('?') == request.redirectUri)
    val parameters = Url(callback).parameters
    for (name in listOf("state", "code", "error")) require(
        (parameters.getAll(name)?.size ?: 0) <= 1
    )
    require(parameters["state"] == request.state)
    val code = parameters["code"]
    parameters["error"]?.let {
        require(code == null)
        if (it == "access_denied") throw GoogleCredentialCancelled()
        throw GoogleCredentialUnavailable()
    }
    requireNotNull(code)
    require(
        code.length in 1..4096 && code.none { it.isWhitespace() || it.code < 32 || it.code == 127 }
    )
    return GoogleCredentialResult.AuthorizationCode(
        code,
        request.verifier,
        request.nonce,
        request.redirectUri,
    )
}
