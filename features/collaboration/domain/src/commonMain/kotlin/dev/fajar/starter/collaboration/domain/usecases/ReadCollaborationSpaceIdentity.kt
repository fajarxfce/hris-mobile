package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*

/** One explicit native metadata read. Never enrolls, polls, clears admission or opens an SDK. */
class ReadCollaborationSpaceIdentity(
    private val identities: CollaborationSpaceIdentityRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner
    ): AppResult<CollaborationSpaceIdentityObservation> =
        readCollaborationSpaceMetadata(
            owner,
            directory,
            sessions,
            server,
            build,
            clock,
            timeSource,
            acquire = { identities.read(owner) },
            valid = { value ->
                value.owner == owner &&
                    value.identity?.let { validCollaborationSpaceIdentity(owner, it) } != false
            },
        )
}
