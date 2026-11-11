package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.CompleteCollaborationSpaceOriginalIncomingReferences
import dev.fajar.starter.common.result.*
import kotlin.test.*

/**
 * Records operation boundaries; actual seek persistence is covered by Company storage contracts.
 */
internal class SpaceOriginalReferenceCompletionFixture {
    val pending = SpaceOriginalPendingReadFixture()
    val provider = pending.provider
    val base = pending.base
    val runtime = base.runtime
    var references = CollaborationHistoryReferences(provider.progress, pending.events, null)
    var batch: CollaborationSpaceOriginalIncomingBatch? = null
    val resolutions = mutableListOf<List<CollaborationHistoryReference>>()
    val committed = mutableListOf<CollaborationHistoryReference>()
    val advances = mutableListOf<Pair<CollaborationHistoryReferences, Boolean>>()
    var resolveResult: AppResult<Boolean> = AppResult.Success(true)
    var advanceResult: AppResult<Boolean> = AppResult.Success(true)
    var commitResolve = true

    val history =
        object : CollaborationHistoryTraversalRepository by pending.history {
            override suspend fun resolve(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> {
                scope(access, traversal)
                assertEquals(
                    this@SpaceOriginalReferenceCompletionFixture.references.progress,
                    expected,
                )
                resolutions += references.toList()
                stage("resolve")?.let {
                    return AppResult.Failed(it)
                }
                if (commitResolve) committed += references
                stage("resolved")?.let {
                    return AppResult.Failed(it)
                }
                return resolveResult
            }

            override suspend fun advanceIncomingReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                source: CollaborationHistoryReferences,
                retryRequired: Boolean,
                limit: Int,
            ): AppResult<Boolean> {
                scope(access, traversal)
                assertEquals(16, limit)
                advances += source to retryRequired
                stage("advance")?.let {
                    return AppResult.Failed(it)
                }
                return advanceResult
            }
        }

    val complete =
        CompleteCollaborationSpaceOriginalIncomingReferences(
            pending.transition.demands,
            provider.metadata,
            provider.registrations,
            history,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
        )

    init {
        acquired()
    }

    fun acquired() {
        batch =
            if (references.events.isEmpty()) null
            else
                CollaborationSpaceOriginalIncomingBatch(
                    runtime,
                    pending.room,
                    references.events.map {
                        CollaborationIncomingHistoryResult(
                            it.event.eventId,
                            CollaborationIncomingHistoryOutcome.Retained(
                                listOf(it.event.eventId),
                                emptyList(),
                            ),
                        )
                    },
                )
    }

    fun outcome(index: Int, outcome: CollaborationIncomingHistoryOutcome) {
        val current = assertNotNull(batch)
        batch =
            current.copy(
                items =
                    current.items.toMutableList().also {
                        it[index] = it[index].copy(outcome = outcome)
                    }
            )
    }

    suspend fun run() = complete(runtime, pending.room, references, batch)

    private suspend fun stage(name: String): Failure? {
        provider.calls += name
        provider.hooks[name]?.invoke()
        return provider.failures[name]
    }

    private fun scope(access: CollaborationLocalAccess, traversal: CollaborationHistoryTraversal) {
        assertEquals(provider.companyAccess, access)
        assertEquals(provider.traversal, traversal)
        assertNotEquals(runtime.deviceId, traversal.deviceId)
    }
}
