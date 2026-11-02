package dev.fajar.starter.app.work

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.sync.domain.*

internal fun collaborationWorkFailure(failure: Failure): SyncResult =
    when (failure.code) {
        "client.collaboration_room_pending",
        "client.collaboration_delivery_pending",
        "client.collaboration_history_pending",
        "client.collaboration_runtime_opening",
        "client.collaboration_close_pending",
        "client.collaboration_queue_busy",
        "client.collaboration_runtime_busy",
        "client.collaboration_history_focus_capacity",
        "client.collaboration_history_progress_changed",
        "client.collaboration_revalidation_required" -> SyncResult.Retry(failure)
        else -> syncFailure(failure)
    }
