package dev.fajar.starter.identity.data.api

import dev.fajar.starter.identity.data.dto.SignInRequest
import dev.fajar.starter.identity.data.dto.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.koin.core.annotation.Single

@Single
class AuthApi(private val client: HttpClient) {
    suspend fun signIn(request: SignInRequest): UserDto =
        client
            .post("auth/login") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            .body()
}
