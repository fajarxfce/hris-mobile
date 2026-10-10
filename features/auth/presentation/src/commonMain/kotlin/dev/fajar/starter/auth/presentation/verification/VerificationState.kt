package dev.fajar.starter.auth.presentation.verification

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.identity.domain.entities.MfaEnrollment

enum class VerificationStep {
    Loading,
    Setup,
    Verify,
    RecoveryCodes,
    Verified,
}

data class VerificationState(
    val step: VerificationStep = VerificationStep.Loading,
    val working: Boolean = false,
    val code: String = "",
    val recovery: Boolean = false,
    val enrollment: MfaEnrollment? = null,
    val enrollmentOperationId: String? = null,
    val recoveryCodes: List<String> = emptyList(),
    val failure: Failure? = null,
    val uncertain: Boolean = false,
) {
    override fun toString() = "VerificationState(step=$step, working=$working, secrets=[redacted])"
}
