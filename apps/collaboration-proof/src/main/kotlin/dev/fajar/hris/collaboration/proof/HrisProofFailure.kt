package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.common.result.Failure

/** Controlled categories only; neither provider descriptions nor request values enter evidence. */
internal class HrisProofFailure(failure: Failure) :
    IllegalStateException("Owned SDK operation failed") {
    val category =
        "HrisProof_${failure.kind.name}_${when (failure.code) {
        "client.collaboration_proof_retry_exhausted" -> "RetryExhausted"
        "client.collaboration_delivery_timeout" -> "DeliveryTimeout"
        "client.collaboration_delivery_pending" -> "DeliveryPending"
        "client.collaboration_history_pending" -> "HistoryPending"
        "client.collaboration_room_pending" -> "RoomPending"
        "client.collaboration_history_focus_capacity" -> "HistoryFocusCapacity"
        "client.collaboration_history_unresolved" -> "HistoryUnresolved"
        "client.collaboration_invalid_history_window" -> "InvalidHistoryWindow"
        "client.collaboration_history_changed" -> "HistoryChanged"
        "client.session_changed" -> "SessionChanged"
        "client.collaboration_close_pending" -> "ClosePending"
        "client.collaboration_runtime_opening" -> "RuntimeOpening"
        "client.collaboration_queue_busy" -> "QueueBusy"
        "client.collaboration_child_pending" -> "ChildPending"
        "client.collaboration_child_interrupted" -> "ChildInterrupted"
        "client.collaboration_children_stopped" -> "ChildrenStopped"
        "client.collaboration_children_full" -> "ChildrenFull"
        "client.collaboration_sync_pending" -> "SyncPending"
        "client.collaboration_sync_stopped" -> "SyncStopped"
        "client.collaboration_sync_interrupted" -> "SyncInterrupted"
        "client.collaboration_runtime_not_open" -> "RuntimeNotOpen"
        "client.collaboration_sdk_not_found" -> "SdkNotFound"
        "client.collaboration_sdk_unauthorized" -> "SdkUnauthorized"
        "client.collaboration_sdk_access_denied" -> "SdkDenied"
        "client.collaboration_sdk_unavailable" -> "SdkUnavailable"
        else -> "Unclassified"
    }}"
}
