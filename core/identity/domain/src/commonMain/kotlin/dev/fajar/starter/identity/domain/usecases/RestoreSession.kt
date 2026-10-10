package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.identity.domain.repositories.SessionRepository

/** Restores local identity without making network availability a startup requirement. */
class RestoreSession(private val repository: SessionRepository) {
    suspend operator fun invoke() = repository.current()
}
