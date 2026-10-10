package dev.fajar.starter.identity.data.api

import dev.fajar.starter.identity.data.dto.*
import dev.fajar.starter.network.HttpClients
import dev.fajar.starter.network.requestJson
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class AuthApi(@Named(HttpClients.Public) private val client: HttpClient) {
    suspend fun completeSso(request: OidcExchangeRequest): AuthResponse =
        client.requestJson("auth/oidc", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun signIn(request: SignInRequest): AuthResponse =
        client.requestJson("auth/login", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun refresh(request: RefreshRequest, operationId: String): AuthResponse =
        client.requestJson("auth/refresh", HttpMethod.Post) {
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
