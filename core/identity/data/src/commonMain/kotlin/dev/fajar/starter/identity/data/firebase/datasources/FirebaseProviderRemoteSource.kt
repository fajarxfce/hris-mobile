package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.firebase.dto.*

interface FirebaseProviderRemoteSource {
    suspend fun password(
        options: FirebaseClientOptions,
        request: FirebasePasswordRequest,
    ): FirebaseProviderResponse

    suspend fun google(options: FirebaseClientOptions, idToken: String): FirebaseProviderResponse

    suspend fun googleCode(
        clientId: String,
        credential: GoogleCredentialResult.AuthorizationCode,
    ): String
}
