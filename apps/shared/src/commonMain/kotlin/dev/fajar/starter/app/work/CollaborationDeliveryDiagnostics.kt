package dev.fajar.starter.app.work

import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import kotlinx.coroutines.TimeoutCancellationException

internal enum class CollaborationDeliveryStage {
    Reconcile,
    ReadPending,
    PrepareRoom,
    EnterTimeline,
    RecoverOriginal,
    ReadPrepared,
    Flush,
    StageMessage,
    DeliverMessages,
    Retire,
}

/** One closed marker per room attempt. No identifiers, payloads or free failure codes. */
internal suspend fun traceCollaborationDelivery(
    action: suspend ((CollaborationDeliveryStage) -> Unit) -> AppResult<Unit>
): AppResult<Unit> {
    var stage = CollaborationDeliveryStage.Reconcile
    try {
        val result = action { stage = it }
        if (result is AppResult.Failed) {
            val reason =
                when (result.failure.code) {
                    "client.collaboration_sdk_unauthorized" -> "SdkAuthentication"
                    "client.collaboration_queue_unresolved" -> "QueueUnresolved"
                    "client.collaboration_delivery_pending" -> "DeliveryPending"
                    "client.collaboration_history_pending" -> "HistoryPending"
                    "client.collaboration_room_retry_required" -> "RoomPreparation"
                    else -> result.failure.kind.name
                }
            recordDelivery(stage, reason)
        }
        return result
    } catch (timeout: TimeoutCancellationException) {
        recordDelivery(stage, "Timeout")
        throw timeout
    }
}

private fun recordDelivery(stage: CollaborationDeliveryStage, reason: String) {
    Diagnostics.record(
        Diagnostic(
            DiagnosticArea.Worker,
            DiagnosticKind.OperationFailed,
            "ChatDelivery_${stage.name}_$reason",
        )
    )
}
