package dev.fajar.starter.security.presentation.settings

import dev.fajar.starter.common.result.Failure

data class LockSettingsState(
    val enabled: Boolean = false,
    val available: Boolean = false,
    val saving: Boolean = false,
    val failure: Failure? = null,
)
