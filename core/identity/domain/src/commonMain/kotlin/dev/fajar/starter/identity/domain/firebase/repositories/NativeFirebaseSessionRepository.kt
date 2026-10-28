package dev.fajar.starter.identity.domain.firebase.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.AuthenticatedUser
import dev.fajar.starter.identity.domain.firebase.entities.FirebaseSignInConfiguration

interface NativeFirebaseSessionRepository {
    suspend fun configuration(): AppResult<FirebaseSignInConfiguration>

    suspend fun exchange(
        idToken: String,
        deviceName: String,
        operationId: String,
    ): AppResult<AuthenticatedUser>
}
