package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationHistoryRecoveryRepository
import dev.fajar.starter.collaboration.domain.usecases.CompleteCollaborationSpaceKeyReferences
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals

internal class SpaceKeyCompletionFixture {
    val reference = SpaceKeyReferenceReadFixture()
    val base = reference.base
    val provider = reference.provider
    val history
        get() = reference.keyHistory

    val checkpoint =
        requireNotNull(reference.progress).let {
            it.copy(
                recovery = CollaborationHistoryRecoveryPosition(reference.generation, it.references)
            )
        }
    var page = CollaborationHistoryReferences(checkpoint, reference.events.toList(), null)
    var items =
        page.events.map {
            CollaborationIncomingHistoryResult(
                it.event.eventId,
                CollaborationIncomingHistoryOutcome.Retained(listOf(it.event.eventId), emptyList()),
            )
        }
    val batch
        get() = CollaborationSpaceKeyPageBatch(base.runtime, history, page, items)

    val attempts = mutableListOf<CollaborationHistoryReferences>()
    val subsets = mutableListOf<List<CollaborationHistoryReference>>()
    var response: AppResult<Boolean> = AppResult.Success(true)
    var commit = true
    var committed = false
    val recovery =
        object : CollaborationHistoryRecoveryRepository by reference.recovery {
            override suspend fun complete(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                attempted: CollaborationHistoryReferences,
                resolved: List<CollaborationHistoryReference>,
                limit: Int,
            ): AppResult<Boolean> {
                reference.scope(access, traversal)
                assertEquals(16, limit)
                attempts += attempted.copy(events = attempted.events.toList())
                subsets += resolved.toList()
                reference.touch("complete")
                if (commit) committed = true
                reference.touch("completed")
                return response
            }
        }
    val complete =
        CompleteCollaborationSpaceKeyReferences(
            reference.bindings,
            provider.registrations,
            recovery,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
        )

    suspend fun run() = complete(base.runtime, history, page, batch)
}
