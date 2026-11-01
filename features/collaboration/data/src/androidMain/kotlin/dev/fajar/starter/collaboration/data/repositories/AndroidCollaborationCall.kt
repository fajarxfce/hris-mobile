package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.mappers.collaborationSdkFailure
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.network.RequestFailureException
import dev.fajar.starter.network.safeApiCall
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind

/** Preserve SDK rejections without retaining provider bodies or exception text. */
internal suspend fun <T> safeAndroidCollaborationCall(operation: suspend () -> T): AppResult<T> =
    safeApiCall {
        try {
            operation()
        } catch (rejected: ClientException.MatrixApi) {
            throw RequestFailureException(
                collaborationSdkFailure(
                    rejected.code,
                    (rejected.kind as? ErrorKind.LimitExceeded)?.retryAfterMs,
                )
            )
        }
    }
