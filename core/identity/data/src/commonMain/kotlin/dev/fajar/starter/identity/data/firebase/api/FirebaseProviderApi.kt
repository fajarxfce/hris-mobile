package dev.fajar.starter.identity.data.firebase.api

import dev.fajar.starter.identity.data.firebase.boundary.FirebaseProviderException
import dev.fajar.starter.identity.data.firebase.dto.*
import dev.fajar.starter.identity.data.firebase.mappers.googleRedirectUri
import dev.fajar.starter.network.HttpClients
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.koin.core.annotation.*

/**
 * Google's fixed Auth REST protocol. No HRIS credential, cookie, IdP access token or refresh is
 * sent.
 */
@Single
class FirebaseProviderApi(@Named(HttpClients.FirebaseAuth) private val client: HttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun password(
        options: FirebaseClientOptions,
        request: FirebasePasswordRequest,
    ): FirebaseProviderResponse =
        request(options, "accounts:signInWithPassword", json.encodeToString(request))

    suspend fun google(options: FirebaseClientOptions, idToken: String): FirebaseProviderResponse {
        require(idToken.length in 32..8192 && idToken.none(Char::isWhitespace))
        val form = listOf("id_token" to idToken, "providerId" to "google.com").formUrlEncode()
        return request(
            options,
            "accounts:signInWithIdp",
            json.encodeToString(FirebaseGoogleRequest(form)),
        )
    }

    suspend fun googleCode(
        clientId: String,
        credential: GoogleCredentialResult.AuthorizationCode,
    ): String {
        require(credential.redirectUri == googleRedirectUri(clientId))
        require(
            credential.code.length in 1..4096 &&
                credential.code.none { it.isWhitespace() || it.code < 32 || it.code == 127 }
        )
        require(credential.verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
        val body =
            listOf(
                    "client_id" to clientId,
                    "code" to credential.code,
                    "code_verifier" to credential.verifier,
                    "redirect_uri" to credential.redirectUri,
                    "grant_type" to "authorization_code",
                )
                .formUrlEncode()
        return client
            .preparePost("https://oauth2.googleapis.com/token") {
                expectSuccess = false
                header(HttpHeaders.AcceptEncoding, "identity")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }
            .execute { response ->
                val accepted = response.status.value == 200
                val bytes = boundedBody(response, if (accepted) 32_768 else 4096)
                try {
                    require(
                        response.contentType()?.withoutParameters() == ContentType.Application.Json
                    )
                    if (!accepted) throw FirebaseProviderException(response.status.value, null)
                    val value =
                        json
                            .parseToJsonElement(bytes.decodeToString())
                            .jsonObject["id_token"]
                            ?.jsonPrimitive
                    require(value?.isString == true)
                    value.content.also {
                        require(it.length in 32..8192 && it.none(Char::isWhitespace))
                    }
                } finally {
                    bytes.fill(0)
                }
            }
    }

    private suspend fun request(
        options: FirebaseClientOptions,
        method: String,
        body: String,
    ): FirebaseProviderResponse =
        client
            .preparePost("https://identitytoolkit.googleapis.com/v1/$method") {
                expectSuccess = false
                url { parameters.append("key", options.apiKey) }
                header(HttpHeaders.AcceptEncoding, "identity")
                options.androidPackage?.let { header("X-Android-Package", it) }
                options.androidCertificate?.let { header("X-Android-Cert", it) }
                options.iosBundleId?.let { header("X-Ios-Bundle-Identifier", it) }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            .execute { response ->
                val accepted = response.status.value == 200
                val bytes = boundedBody(response, if (accepted) 32_768 else 4096)
                try {
                    require(
                        response.contentType()?.withoutParameters() == ContentType.Application.Json
                    )
                    if (!accepted) {
                        val code =
                            runCatching {
                                    json
                                        .parseToJsonElement(bytes.decodeToString())
                                        .jsonObject["error"]
                                        ?.jsonObject
                                        ?.get("message")
                                        ?.jsonPrimitive
                                        ?.content
                                        ?.substringBefore(" : ")
                                        ?.takeIf { it.matches(Regex("[A-Z0-9_]{1,100}")) }
                                }
                                .getOrNull()
                        throw FirebaseProviderException(response.status.value, code)
                    }
                    json.decodeFromString<FirebaseProviderResponse>(bytes.decodeToString())
                } finally {
                    bytes.fill(0)
                }
            }

    private suspend fun boundedBody(response: HttpResponse, maximum: Int): ByteArray {
        require(
            response.headers[HttpHeaders.ContentEncoding].let {
                it == null || it.equals("identity", true)
            }
        )
        require(
            response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
                it in 0..maximum.toLong()
            } != false
        )
        val channel = response.bodyAsChannel()
        val bytes = channel.readRemaining(maximum.toLong() + 1).readByteArray()
        try {
            channel.closedCause?.let { throw it }
            currentCoroutineContext().ensureActive()
            require(bytes.size <= maximum)
            return bytes
        } catch (error: Throwable) {
            bytes.fill(0)
            throw error
        }
    }
}
