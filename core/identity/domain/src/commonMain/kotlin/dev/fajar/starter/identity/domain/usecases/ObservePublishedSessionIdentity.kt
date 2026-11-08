package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.identity.domain.repositories.SessionIdentityRepository
import kotlinx.coroutines.flow.distinctUntilChanged

/** Passive identity changes for optional observers, independent of session restoration. */
class ObservePublishedSessionIdentity(private val identities: SessionIdentityRepository) {
    operator fun invoke() = identities.observePublished().distinctUntilChanged()
}
