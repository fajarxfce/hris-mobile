package dev.fajar.starter.location.domain.usecases

import dev.fajar.starter.location.domain.entities.LocationSettings
import dev.fajar.starter.location.domain.repositories.LocationRepository

/** Invoke only for an explicit Settings action; never as a safe-call side effect. */
class OpenLocationSettings(private val repository: LocationRepository) {
    suspend operator fun invoke(target: LocationSettings) = repository.openSettings(target)
}
