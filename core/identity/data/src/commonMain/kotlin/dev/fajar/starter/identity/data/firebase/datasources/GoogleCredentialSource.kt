package dev.fajar.starter.identity.data.firebase.datasources

interface GoogleCredentialSource : AutoCloseable {
    val supported: Boolean

    suspend fun open(webClientId: String): GoogleCredentialSession

    override fun close() {}
}

interface GoogleCredentialSession : AutoCloseable {
    suspend fun idToken(): String
}

class UnavailableGoogleCredentialSource : GoogleCredentialSource {
    override val supported = false

    override suspend fun open(webClientId: String): GoogleCredentialSession =
        throw UnsupportedOperationException("Google sign-in is unavailable")
}
