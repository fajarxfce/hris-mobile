package dev.fajar.starter.security.data.access.datasources

import dev.fajar.starter.security.data.access.dto.AccessResponse

interface AccessRemoteDataSource {
    suspend fun fetch(sessionId: String): AccessResponse
}
