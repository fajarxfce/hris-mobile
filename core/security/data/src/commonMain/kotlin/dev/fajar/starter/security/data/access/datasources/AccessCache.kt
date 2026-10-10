package dev.fajar.starter.security.data.access.datasources

import dev.fajar.starter.security.data.access.dto.AccessResponse
import kotlinx.coroutines.flow.Flow

interface AccessCache {
    fun observe(sessionId: String): Flow<AccessResponse?>

    fun read(sessionId: String): AccessResponse?

    fun remove(sessionId: String)

    fun write(sessionId: String, response: AccessResponse)
}
