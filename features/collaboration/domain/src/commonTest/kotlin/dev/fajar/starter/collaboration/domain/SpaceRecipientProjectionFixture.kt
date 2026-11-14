package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecipientProjectionRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceRecipientProjection
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.*

internal class SpaceRecipientProjectionFixture {
    val owner = SpaceRecipientDraftFixture()
    val base = owner.base
    val access = owner.access
    val peer = owner.original.peerAccountId
    val original =
        CollaborationSpaceRecipientSubmission(
            owner.original,
            base.generation,
            owner.original.savedAt,
        )
    val retained = CollaborationSpaceRecipientProjection(original.nextDraft, listOf(original))
    val facts =
        MutableStateFlow<AppResult<CollaborationSpaceRecipientProjection>>(
            AppResult.Success(retained)
        )
    var reads = 0
    var stops = 0
    var content: () -> Flow<AppResult<CollaborationSpaceRecipientProjection>> = { facts }
    val repository =
        object : CollaborationSpaceRecipientProjectionRepository {
            override fun observe(access: CollaborationSpaceLocalAccess, peerAccountId: String) =
                flow {
                    check(
                        access == this@SpaceRecipientProjectionFixture.access &&
                            peerAccountId == peer
                    )
                    reads++
                    try {
                        emitAll(content())
                    } finally {
                        stops++
                    }
                }
        }

    fun observe() =
        ObserveCollaborationSpaceRecipientProjection(
            repository,
            owner.directory,
            owner.sessions,
            base.native.server,
        )
}
