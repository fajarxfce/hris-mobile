package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceRecipientMessageIntent
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDirectoryRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceComposerProgress
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.flow.*

internal class SpaceRecipientProgressFixture {
    val base = SpaceComposerProgressFixture()
    val access = base.access
    val canonical = SpaceMessageUseCaseFixture().canonical!!
    val first = SpaceRecipientProjectionFixture().original
    val second = original(2)
    val requested = MutableStateFlow(listOf(first))
    var identityFacts: () -> Flow<AppResult<Session?>> = { base.identity }
    var admissionFacts: () -> Flow<AppResult<CollaborationSpaceDirectoryAdmission>> = {
        base.membership.gate
    }
    val sessions =
        object : SessionRepository by base.sessions {
            override fun observe() = identityFacts()
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override fun observeAdmission(access: CollaborationSpaceLocalAccess) = admissionFacts()
        }

    fun observer() =
        ObserveCollaborationSpaceComposerProgress(
            base.messages,
            directory,
            sessions,
            base.native.server,
        )

    fun observe() = observer().recipients(access, canonical, requested)

    fun record(original: CollaborationSpaceRecipientSubmission = first) =
        CollaborationSpaceMessageRecord(
            collaborationSpaceRecipientMessageIntent(access, original, canonical.id),
            0,
            CollaborationMessageState.Queued,
        )

    fun original(index: Int) =
        first.copy(
            original =
                first.original.copy(
                    revision = base.id(index + 20),
                    text = "Recipient original $index",
                ),
            nextRevision = base.id(index + 2000),
        )
}
