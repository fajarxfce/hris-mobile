package dev.fajar.starter.securestorage

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSError

class AppleCredentialStore(private val client: AppleCredentialClient) : CredentialStore {
    override val persistent = true

    override suspend fun read(): String? =
        withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { continuation ->
                client.read { value, error ->
                    if (continuation.isActive) {
                        if (error != null)
                            continuation.resumeWithException(AppleCredentialException(error))
                        else continuation.resume(value)
                    }
                }
            }
        }

    override suspend fun write(value: String?): Unit =
        withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { continuation ->
                client.write(value) { error ->
                    if (continuation.isActive) {
                        if (error != null)
                            continuation.resumeWithException(AppleCredentialException(error))
                        else continuation.resume(Unit)
                    }
                }
            }
        }
}

class AppleCredentialException(val nativeError: NSError) : Exception("Keychain operation failed.")
