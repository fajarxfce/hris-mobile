package dev.fajar.starter.identity.data.firebase.api

import dev.fajar.starter.identity.data.dto.AuthResponse
import dev.fajar.starter.identity.data.firebase.dto.*
import dev.fajar.starter.network.HttpClients
import dev.fajar.starter.network.requestJson
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class NativeFirebaseApi(@Named(HttpClients.Public) private val client: HttpClient) {
    suspend fun configuration(): NativeFirebaseConfigurationDto =
        client.requestJson("auth/native/firebase", maximumBytes = 4096)

    suspend fun exchange(request: NativeFirebaseLoginDto, operationId: String): AuthResponse =
        client.requestJson("auth/native/firebase/login", HttpMethod.Post, maximumBytes = 16_384) {
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
