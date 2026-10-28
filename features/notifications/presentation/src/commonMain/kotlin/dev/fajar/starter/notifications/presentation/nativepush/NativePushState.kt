package dev.fajar.starter.notifications.presentation.nativepush

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.identity.domain.push.entities.NativePushStatus

data class NativePushState(
    val sessionId: String? = null,
    val status: NativePushStatus? = null,
    val busy: Boolean = false,
    val failure: Failure? = null,
)

sealed interface NativePushEvent {
    data object Started : NativePushEvent

    data object Stopped : NativePushEvent

    data object EnableRequested : NativePushEvent

    data object DisableRequested : NativePushEvent

    data object RetryRequested : NativePushEvent
}
