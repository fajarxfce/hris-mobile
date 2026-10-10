package dev.fajar.starter.auth.presentation.login

import dev.fajar.starter.common.result.Failure

data class LoginState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    val failure: Failure? = null,
)
