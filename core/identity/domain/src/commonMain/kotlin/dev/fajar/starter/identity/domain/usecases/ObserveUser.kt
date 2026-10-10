package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.identity.domain.repositories.IdentityRepository

class ObserveUser(private val repository: IdentityRepository) {
    operator fun invoke() = repository.observeUser()
}
