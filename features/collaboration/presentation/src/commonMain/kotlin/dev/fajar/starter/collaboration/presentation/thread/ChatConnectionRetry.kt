package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.policies.collaborationContentResponseClosesRead
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind

/** Read-only foreground recovery; explicit provisioning failures never acquire new intent. */
internal fun chatCanReconnect(failure: Failure): Boolean =
    !collaborationContentResponseClosesRead(failure) &&
        failure.kind in
            setOf(
                FailureKind.Network,
                FailureKind.Timeout,
                FailureKind.Service,
                FailureKind.Unavailable,
                FailureKind.Unexpected,
            ) &&
        failure.code !in
            setOf(
                "client.collaboration_history_focus_capacity",
                "client.collaboration_handover_pending",
                "client.collaboration_handover_changed",
            )

internal fun chatReconnectDelay(attempt: Int, failure: Failure): Long {
    val backoff = (2_000L shl attempt.coerceIn(0, 5)).coerceAtMost(60_000)
    val hint = (failure.retryAfterSeconds ?: 0).coerceIn(0, Long.MAX_VALUE / 1_000) * 1_000
    return maxOf(backoff, hint)
}
