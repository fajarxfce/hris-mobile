package dev.fajar.starter.auth.presentation.login

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.ValidationIssue
import dev.fajar.starter.identity.domain.sso.entities.SsoProvider

data class LoginState(
    val providers: List<SsoProvider> = emptyList(),
    val pendingProviderId: String? = null,
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    val failure: Failure? = null,
    val fieldErrors: Map<String, ValidationIssue> = emptyMap(),
    val validated: Boolean = false,
) {
    override fun toString(): String = "LoginState(submitting=$submitting, credentials=[redacted])"
}
