@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.security.data.lock.datasources

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import platform.LocalAuthentication.*

/** Each call owns its LAContext and invalidates it on completion and cancellation. */
class AppleDeviceAuthenticationSource : DeviceAuthenticationSource {
    override suspend fun available(): Boolean =
        withContext(Dispatchers.Main) {
            val context = LAContext()
            try {
                context.canEvaluatePolicy(
                    LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                    error = null,
                )
            } finally {
                context.invalidate()
            }
        }

    override suspend fun authenticate(): Boolean =
        withContext(Dispatchers.Main) {
            val context = LAContext()
            try {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { context.invalidate() }
                    context.evaluatePolicy(
                        LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                        localizedReason = "Unlock Fluent Starter",
                    ) { success, error ->
                        if (continuation.isActive) {
                            if (error == null) continuation.resume(success)
                            else if (
                                error.code in
                                    setOf(LAErrorUserCancel, LAErrorAppCancel, LAErrorSystemCancel)
                            )
                                continuation.resume(false)
                            else
                                continuation.resumeWithException(
                                    AppleDeviceAuthenticationException(error)
                                )
                        }
                    }
                }
            } finally {
                context.invalidate()
            }
        }
}
