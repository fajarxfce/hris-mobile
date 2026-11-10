package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceIncomingReferencesFixture {
    val provider = SpaceProviderHistoryFixture()
    val base = provider.base
    val access = provider.access
    val key = provider.traversal
    var source =
        CollaborationHistoryReferences(
            provider.checkpoint(),
            provider.page.physical.events.take(16).mapIndexed { index, event ->
                CollaborationHistoryReference(index.toLong(), event)
            },
            "after-16",
        )
    var batch: CollaborationSpaceIncomingBatch? =
        CollaborationSpaceIncomingBatch(
            provider.runtime,
            key.conversationId,
            key.roomId,
            source.events.map {
                CollaborationIncomingHistoryResult(
                    it.event.eventId,
                    CollaborationIncomingHistoryOutcome.Retained(
                        listOf(it.event.eventId),
                        emptyList(),
                    ),
                )
            },
            provider.identity,
        )
    val resolved = mutableListOf<List<CollaborationHistoryReference>>()
    val advanced = mutableListOf<Pair<CollaborationHistoryReferences, Boolean>>()
    var resolveResult: AppResult<Boolean> = AppResult.Success(true)
    var advanceResult: AppResult<Boolean> = AppResult.Success(true)
    var references: () -> Flow<AppResult<CollaborationHistoryReferences>> = {
        flow { emit(base.read("references") { source }) }
    }
    val history =
        object : CollaborationSpaceHistoryTraversalRepository by provider.history {
            override suspend fun progress(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> {
                assertEquals(this@SpaceIncomingReferencesFixture.access, access)
                assertEquals(key, traversal)
                return base.read("pendingProgress") { source.progress }
            }

            override fun observeReferences(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationHistoryReferences>> {
                assertEquals(this@SpaceIncomingReferencesFixture.access, access)
                assertEquals(key, traversal)
                assertEquals(source.progress?.incoming?.pendingAfter, after)
                assertEquals(16, limit)
                return references()
            }

            override suspend fun resolve(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceIncomingReferencesFixture.access, access)
                assertEquals(key, traversal)
                assertEquals(source.progress, expected)
                resolved += references.toList()
                val result = base.read("resolve") { Unit }
                return if (result is AppResult.Failed) result else resolveResult
            }

            override suspend fun advanceIncomingReferences(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                source: CollaborationHistoryReferences,
                retryRequired: Boolean,
                limit: Int,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceIncomingReferencesFixture.access, access)
                assertEquals(key, traversal)
                assertEquals(16, limit)
                advanced += source to retryRequired
                val result = base.read("advance") { Unit }
                return if (result is AppResult.Failed) result else advanceResult
            }
        }
    val read =
        ReadCollaborationSpaceIncomingReferences(
            history,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
        )
    val complete =
        CompleteCollaborationSpaceIncomingReferences(
            history,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
        )

    suspend fun finish() = complete(access, key, source, batch)
}
