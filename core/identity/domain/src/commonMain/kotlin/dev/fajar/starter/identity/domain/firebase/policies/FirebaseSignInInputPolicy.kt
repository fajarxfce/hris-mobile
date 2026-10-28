package dev.fajar.starter.identity.domain.firebase.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.firebase.entities.FirebaseSignInInput
import dev.fajar.starter.identity.domain.validation.signInViolations

fun normalizedFirebaseSignIn(input: FirebaseSignInInput): AppResult<FirebaseSignInInput> {
    if (input !is FirebaseSignInInput.EmailPassword) return AppResult.Success(input)
    val normalized = input.copy(email = input.email.trim().lowercase())
    val violations = signInViolations(normalized.email, normalized.password).toMutableMap()
    if (normalized.email.length > 254) violations["email"] = ValidationIssue.Invalid
    if (normalized.password.length > 4096) violations["password"] = ValidationIssue.Invalid
    return if (violations.isEmpty()) AppResult.Success(normalized)
    else
        AppResult.Failed(
            Failure(
                FailureKind.Validation,
                "Check the information and try again.",
                violations.keys.first(),
                violations,
            )
        )
}
