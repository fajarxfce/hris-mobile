package dev.fajar.starter.identity.domain.validation

import dev.fajar.starter.common.result.ValidationIssue

/** Domain validation shared by validation-only and submitting use cases. */
fun signInViolations(email: String, password: String): Map<String, ValidationIssue> = buildMap {
    if (email.isBlank()) put("email", ValidationIssue.Required)
    else if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email.trim()))
        put("email", ValidationIssue.Invalid)
    if (password.isBlank()) put("password", ValidationIssue.Required)
}
