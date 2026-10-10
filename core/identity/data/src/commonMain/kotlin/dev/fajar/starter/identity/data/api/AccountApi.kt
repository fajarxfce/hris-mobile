package dev.fajar.starter.identity.data.api

import dev.fajar.starter.identity.data.dto.AccountSnapshotDto
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import org.koin.core.annotation.*

@Single
class AccountApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun current(sessionId: String): AccountSnapshotDto =
        client.requestJson("me") { forSession(sessionId) }
}
