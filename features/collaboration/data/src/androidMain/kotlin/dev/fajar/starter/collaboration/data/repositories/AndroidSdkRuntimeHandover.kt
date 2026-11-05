package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.CancellationException

/** Keep a known SDK rejection through failing cleanup without converting cancellation. */
internal suspend fun <T : AndroidCollaborationRuntimeLease?> handoverAndroidSdkRuntime(
    block: suspend ((Failure) -> Unit) -> T
): AppResult<T> {
    var opened: T? = null
    var received: Failure? = null
    try {
        val result = safeStorageCall {
            block { failure ->
                    received =
                        received?.let {
                            collaborationSdkAcquisitionFailure(AppResult.Failed(it), failure)
                        } ?: failure
                }
                .also { opened = it }
        }
        return if (result is AppResult.Failed && received != null)
            AppResult.Failed(requireNotNull(received))
        else result
    } catch (cancelled: CancellationException) {
        opened?.close()
        throw cancelled
    }
}
