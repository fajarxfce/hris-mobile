package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Compare observed immutable originals. Invisible intermediate archival needs its own durable wake;
 * snapshots do not establish that every transition was observed.
 */
fun collaborationSpaceInboxRepairDemand(
    access: CollaborationSpaceLocalAccess,
    previous: List<CollaborationSpaceMessageRecord>?,
    current: List<CollaborationSpaceMessageRecord>,
): AppResult<CollaborationSpaceInboxRepairDemand> {
    val latest = collaborationSpaceMessageInventory(access, current)
    val before = previous?.let { collaborationSpaceMessageInventory(access, it) }
    if (latest == null || previous != null && before == null)
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val oldById = before.orEmpty().associateBy { it.intent.id }
    if (latest.any { oldById[it.intent.id]?.intent?.let { old -> old != it.intent } == true })
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val currentIds = latest.map { it.intent.id }.toSet()
    val changed =
        latest.filter {
            it.intent.editTarget == null &&
                oldById[it.intent.id] != it &&
                (it.state is CollaborationMessageState.Sent ||
                    it.state == CollaborationMessageState.Cancelled)
        }
    val retired =
        before.orEmpty().filter { it.intent.editTarget == null && it.intent.id !in currentIds }
    val targets =
        (changed + retired)
            .map { CollaborationSpaceInboxRepairTarget(it.intent.conversationId, it.intent.id) }
            .distinct()
    return AppResult.Success(
        CollaborationSpaceInboxRepairDemand(
            targets.take(128),
            previous == null || targets.size > 128,
        )
    )
}
