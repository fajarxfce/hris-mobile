package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.identity.domain.repositories.IdentityRepository

class SignOut(private val repository: IdentityRepository) {
    suspend operator fun invoke() = repository.signOut()
}
