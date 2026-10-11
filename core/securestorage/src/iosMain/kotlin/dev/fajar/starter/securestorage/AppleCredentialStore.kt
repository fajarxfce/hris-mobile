package dev.fajar.starter.securestorage

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSError

class AppleCredentialStore(private val client: AppleCredentialClient) : AtomicCredentialStore {
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

    override suspend fun writeIfAbsent(value: String): String =
        withContext(Dispatchers.Default) {
            require(value.encodeToByteArray().size <= 65_536)
            suspendCancellableCoroutine { continuation ->
                client.writeIfAbsent(value) { stored, error ->
                    if (continuation.isActive) {
                        when {
                            error != null ->
                                continuation.resumeWithException(AppleCredentialException(error))
                            stored == null ->
                                continuation.resumeWithException(
                                    IllegalStateException("Credential creation returned no record.")
                                )
                            else -> continuation.resume(stored)
                        }
                    }
                }
            }
        }
}

class AppleCredentialException(val nativeError: NSError) : Exception("Keychain operation failed.")
