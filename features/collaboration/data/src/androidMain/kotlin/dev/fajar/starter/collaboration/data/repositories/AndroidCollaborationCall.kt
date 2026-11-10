package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.mappers.collaborationSdkFailure
import dev.fajar.starter.collaboration.data.sdk.sdkExceptionCategory
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.network.RequestFailureException
import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.observability.*
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind

internal enum class AndroidCollaborationOperation {
    Timeline,
    ReadReceipt,
    QueueSnapshot,
    QueueOriginals,
    QueueSettle,
    QueueOrigin,
    QueueMatch,
    QueueEnqueue,
    QueueResume,
    PusherSession,
    QueueOpen,
    HistorySession,
    HistoryWindow,
    RawEvent,
}

/** Preserve SDK rejections without retaining provider bodies or exception text. */
internal suspend fun <T> safeAndroidCollaborationCall(
    stage: AndroidCollaborationOperation,
    operation: suspend () -> T,
): AppResult<T> =
    safeApiCall(
        onException = { exception ->
            Diagnostics.record(
                Diagnostic(
                    DiagnosticArea.Network,
                    DiagnosticKind.OperationFailed,
                    "Chat${stage.name}_${(exception as? RequestFailureException)?.failure?.kind?.name ?: sdkExceptionCategory(exception)}",
                )
            )
        }
    ) {
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
