package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * A changed original inventory permits one bounded seed attempt, including previously unfinished
 * current originals. Request-only emissions never retry a seed. No command is marked completed.
 */
fun collaborationSpaceInboxSeedDemand(
    access: CollaborationSpaceLocalAccess,
    previous: List<CollaborationSpaceMessageRecord>?,
    current: List<CollaborationSpaceMessageRecord>,
    retryCurrent: Boolean = false,
): AppResult<List<CollaborationSpaceMessageIntent>> {
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
    val originals = latest.filter { it.intent.editTarget == null }
    val retained = before?.filter { it.intent.editTarget == null }
    if (originals == retained) return AppResult.Success(emptyList())
    val ids = originals.map { it.intent.id }.toSet()
    val retired = retained.orEmpty().filter { it.intent.id !in ids }
    val oldIds = retained.orEmpty().map { it.intent.id }.toSet()
    val selected = if (retryCurrent) originals else originals.filter { it.intent.id !in oldIds }
    // Each input is capped at 128: retain all 256 possible distinct originals, never take/drop.
    return AppResult.Success(
        (selected + retired).map { it.intent }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    )
}
