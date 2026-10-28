package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.dto.AuthResponse
import dev.fajar.starter.identity.data.firebase.dto.*

interface NativeFirebaseRemoteSource {
    suspend fun configuration(): NativeFirebaseConfigurationDto

    suspend fun exchange(request: NativeFirebaseLoginDto, operationId: String): AuthResponse
}
