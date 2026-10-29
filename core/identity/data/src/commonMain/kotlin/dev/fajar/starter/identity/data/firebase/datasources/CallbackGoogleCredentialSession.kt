package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.firebase.dto.GoogleCredentialResult
import kotlinx.coroutines.CompletableDeferred

interface GoogleCredentialCallback {
    fun success(credential: GoogleCredentialResult)

    fun failure(error: Exception)
}

/** Host invokes request, callbacks and disposal on Main; every request has its own result. */
class CallbackGoogleCredentialSession(
    private val request: (GoogleCredentialCallback) -> AutoCloseable,
    private val onClosed: () -> Unit,
) : GoogleCredentialSession {
    private val result = CompletableDeferred<GoogleCredentialResult>()
    private var owned: AutoCloseable? = null
    private var started = false
    private var closed = false
    private val callback =
        object : GoogleCredentialCallback {
            override fun success(credential: GoogleCredentialResult) {
                if (!closed) result.complete(credential)
            }

            override fun failure(error: Exception) {
                if (!closed) result.completeExceptionally(error)
            }
        }

    override suspend fun credential(): GoogleCredentialResult {
        check(!closed && !started) { "Google credential resource cannot be reused" }
        started = true
        val acquired = request(callback)
        if (closed) acquired.close() else owned = acquired
        return result.await()
    }

    override fun close() {
        if (closed) return
        closed = true
        result.cancel()
        val resource = owned
        owned = null
        try {
            resource?.close()
        } finally {
            onClosed()
        }
    }
}
