package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.firebase.dto.*

interface FirebaseProviderRemoteSource {
    suspend fun password(
        options: FirebaseClientOptions,
        request: FirebasePasswordRequest,
    ): FirebaseProviderResponse

    suspend fun google(options: FirebaseClientOptions, idToken: String): FirebaseProviderResponse
}
