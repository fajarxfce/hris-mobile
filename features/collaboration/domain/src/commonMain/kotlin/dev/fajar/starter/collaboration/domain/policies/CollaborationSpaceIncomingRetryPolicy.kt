package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

private const val INCOMING_QUEUE_BUSY = "client.collaboration_incoming_queue_busy"

/** A rejected compare-and-set leaves the captured work eligible for the next serialized turn. */
fun collaborationSpaceIncomingQueueContention() =
    AppResult.Failed(
        Failure(
            FailureKind.Unavailable,
            "Messages are waiting to sync.",
            code = INCOMING_QUEUE_BUSY,
            retryAfterSeconds = 1,
        )
    )

fun isCollaborationSpaceIncomingQueueContention(failure: Failure?): Boolean =
    failure?.kind == FailureKind.Unavailable && failure.code == INCOMING_QUEUE_BUSY

fun validCollaborationSpaceIncomingRetry(value: CollaborationSpaceIncomingRetry): Boolean =
    value.attemptedAtEpochMillis in 0..9_007_199_254_440_991L &&
        value.eligibleAtEpochMillis in 30_000..9_007_199_254_740_991L &&
        value.failures in 1..5 &&
        value.eligibleAtEpochMillis - value.attemptedAtEpochMillis ==
            incomingRetryDelay(value.failures)

/** A backward wall-clock adjustment cannot hide retained work indefinitely. */
fun eligibleCollaborationSpaceIncomingRoom(
    room: CollaborationSpaceIncomingRoomDemand,
    now: Long,
): Boolean {
    if (now !in 0..9_007_199_254_440_991L || !validCollaborationSpaceIncomingRoomDemand(room))
        return false
    val retry = room.retry ?: return true
    return now < retry.attemptedAtEpochMillis || now >= retry.eligibleAtEpochMillis
}

fun nextCollaborationSpaceIncomingRetry(
    previous: CollaborationSpaceIncomingRetry?,
    now: Long,
): CollaborationSpaceIncomingRetry? {
    if (
        now !in 0..9_007_199_254_440_991L ||
            previous?.let { !validCollaborationSpaceIncomingRetry(it) } == true
    )
        return null
    val failures = ((previous?.failures ?: 0) + 1).coerceAtMost(5)
    return CollaborationSpaceIncomingRetry(now, now + incomingRetryDelay(failures), failures)
}

fun eligibleCollaborationSpaceIncomingPhysicalRoom(
    room: CollaborationSpaceIncomingPhysicalRoomDemand,
    now: Long,
): Boolean {
    if (now !in 0..9_007_199_254_440_991L || !validCollaborationSpaceIncomingPhysicalRoom(room))
        return false
    val retry = room.retry ?: return true
    return now < retry.attemptedAtEpochMillis || now >= retry.eligibleAtEpochMillis
}

private fun incomingRetryDelay(failures: Int): Long =
    minOf(300_000L, 30_000L * (1 shl (failures - 1)))
