package dev.fajar.starter.security.presentation.lock

import dev.fajar.starter.common.result.Failure

data class AppLockState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val sessionId: String? = null,
    val locked: Boolean = true,
    val authenticating: Boolean = false,
    val resetting: Boolean = false,
    val failure: Failure? = null,
)
