package dev.fajar.starter.security.data.access.api

import dev.fajar.starter.network.*
import dev.fajar.starter.security.data.access.dto.AccessResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import org.koin.core.annotation.*

@Single
class AccessApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun fetch(sessionId: String): AccessResponse =
        client.get("me/access") { forSession(sessionId) }.body()
}
