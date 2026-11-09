package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.CancellationException

/** Keep a known SDK rejection through failing cleanup without converting cancellation. */
internal suspend fun <T> handoverAndroidSdkRuntime(
    map: (AndroidCollaborationRuntimeLease?) -> T,
    block: suspend ((Failure) -> Unit) -> AndroidCollaborationRuntimeLease?,
): AppResult<T> {
    var opened: AndroidCollaborationRuntimeLease? = null
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
        received?.let {
            opened?.close()
            return AppResult.Failed(it)
        }
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                safeStorageCall { map(result.value) }
                    .also { if (it is AppResult.Failed) opened?.close() }
        }
    } catch (cancelled: CancellationException) {
        opened?.close()
        throw cancelled
    }
}
