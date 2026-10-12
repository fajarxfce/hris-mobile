package dev.fajar.starter.fileio

import android.os.CancellationSignal
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The calling operation owns the request, worker and cancellation signal. Return detached values;
 * native resources such as cursors must be closed within the operation, before result delivery.
 */
internal suspend fun <T> androidProviderCall(operation: (CancellationSignal) -> T): T =
    coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            val worker =
                launch(Dispatchers.IO) {
                    try {
                        val result = operation(signal)
                        continuation.resume(result) { _, _, _ -> }
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            continuation.invokeOnCancellation {
                signal.cancel()
                worker.cancel()
            }
        }
    }
