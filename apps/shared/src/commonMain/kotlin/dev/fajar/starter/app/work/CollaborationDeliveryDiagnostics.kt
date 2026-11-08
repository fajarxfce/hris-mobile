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
    SettleCompletedQueue,
    ReadPrepared,
    Flush,
    StageMessage,
    DeliverMessages,
    Retire,
    AcquireRetirementHistory,
    RetireCommands,
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
                    "client.collaboration_queue_mapping_bounds" -> "QueueMappingBounds"
                    "client.collaboration_queue_mapping_owner" -> "QueueMappingOwner"
                    "client.collaboration_queue_mapping_invalid_record" ->
                        "QueueMappingInvalidRecord"
                    "client.collaboration_queue_mapping_cancelled" -> "QueueMappingCancelled"
                    "client.collaboration_queue_mapping_staging" -> "QueueMappingStaging"
                    "client.collaboration_queue_mapping_device" -> "QueueMappingDevice"
                    "client.collaboration_queue_mapping_native_family" -> "QueueMappingNativeFamily"
                    "client.collaboration_queue_mapping_room" -> "QueueMappingRoom"
                    "client.collaboration_queue_mapping_legacy_receipt" ->
                        "QueueMappingLegacyReceipt"
                    "client.collaboration_queue_mapping_missing_receipt" ->
                        "QueueMappingMissingReceipt"
                    "client.collaboration_queue_mapping_duplicate_transaction" ->
                        "QueueMappingDuplicateTransaction"
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
