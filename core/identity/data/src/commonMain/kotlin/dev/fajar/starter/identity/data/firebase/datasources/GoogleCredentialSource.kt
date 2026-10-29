package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.firebase.dto.GoogleCredentialResult

interface GoogleCredentialSource : AutoCloseable {
    val supported: Boolean

    suspend fun open(clientId: String): GoogleCredentialSession

    override fun close() {}
}

interface GoogleCredentialSession : AutoCloseable {
    suspend fun credential(): GoogleCredentialResult
}

class UnavailableGoogleCredentialSource : GoogleCredentialSource {
    override val supported = false

    override suspend fun open(clientId: String): GoogleCredentialSession =
        throw UnsupportedOperationException("Google sign-in is unavailable")
}
