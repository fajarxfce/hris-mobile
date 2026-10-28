package dev.fajar.starter.identity.data.push.datasources

import dev.fajar.starter.identity.data.push.dto.*

interface NativePushRemoteSource {
    suspend fun read(sessionId: String): NativePushRegistrationDto

    suspend fun save(
        sessionId: String,
        operationId: String,
        input: SaveNativePushRequest,
    ): NativePushReceiptDto

    suspend fun disable(
        sessionId: String,
        operationId: String,
        input: DisableNativePushRequest,
    ): NativePushReceiptDto
}
