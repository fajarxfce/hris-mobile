package dev.fajar.starter.auth.presentation.sessions

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.identity.domain.entities.DeviceSessions

data class SessionsState(
    val directory: DeviceSessions? = null,
    val confirmingId: String? = null,
    val working: Boolean = false,
    val failure: Failure? = null,
)
