package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.identity.domain.validation.signInViolations

class ValidateSignIn {
    operator fun invoke(email: String, password: String) = signInViolations(email, password)
}
