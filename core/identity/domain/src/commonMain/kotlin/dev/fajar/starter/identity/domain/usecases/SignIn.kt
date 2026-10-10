package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.identity.domain.entities.User
import dev.fajar.starter.identity.domain.repositories.IdentityRepository

class SignIn(private val repository: IdentityRepository) {
    suspend operator fun invoke(email: String, password: String): AppResult<User> {
        val normalizedEmail = email.trim().lowercase()
        if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(normalizedEmail)) {
            return AppResult.Failed(
                Failure(FailureKind.Validation, "Enter a valid email address.", "email")
            )
        }
        if (password.isBlank()) {
            return AppResult.Failed(
                Failure(FailureKind.Validation, "Enter your password.", "password")
            )
        }
        return repository.signIn(normalizedEmail, password)
    }
}
