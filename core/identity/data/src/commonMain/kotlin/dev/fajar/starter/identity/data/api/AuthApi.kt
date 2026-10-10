package dev.fajar.starter.identity.data.api

import dev.fajar.starter.identity.data.dto.*
import dev.fajar.starter.network.HttpClients
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class AuthApi(@Named(HttpClients.Public) private val client: HttpClient) {
    suspend fun completeSso(request: OidcExchangeRequest): AuthResponse =
        client
            .post("auth/oidc") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            .body()

    suspend fun signIn(request: SignInRequest): AuthResponse =
        client
            .post("auth/login") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            .body()

    suspend fun refresh(request: RefreshRequest): AuthResponse =
        client
            .post("auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            .body()
}
