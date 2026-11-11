package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReconcileCollaborationSpaceOriginalHistoryReferences
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.Flow

internal class SpaceOriginalHistoryReconciliationFixture {
    val pending = SpaceOriginalPendingReadFixture()
    val provider = pending.provider
    val base = pending.base
    val runtime = base.runtime
    var references = CollaborationHistoryReferences(provider.progress, pending.events, null)
    val originals = mutableMapOf<String, CollaborationHistoryEventFact>()
    val replacements = mutableMapOf<String, CollaborationReplacementEventFact>()
    val attempts = mutableListOf<List<CollaborationHistoryReference>>()
    val committed = mutableListOf<CollaborationHistoryReference>()
    var commit = true
    var response: AppResult<Boolean> = AppResult.Success(true)

    private suspend fun <T> read(stage: String, value: () -> T): AppResult<T> {
        provider.calls += stage
        provider.hooks[stage]?.invoke()
        return provider.failures[stage]?.let { AppResult.Failed(it) } ?: AppResult.Success(value())
    }

    val traversal =
        object : CollaborationHistoryTraversalRepository by pending.history {
            override suspend fun resolve(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> {
                assertEquals(provider.companyAccess, access)
                assertEquals(provider.traversal, traversal)
                assertEquals(
                    this@SpaceOriginalHistoryReconciliationFixture.references.progress,
                    expected,
                )
                attempts += references.toList()
                val entered = read("resolve") { Unit }
                if (entered is AppResult.Failed) return entered
                if (!commit) return AppResult.Success(false)
                committed += references
                val completed = read("resolved") { Unit }
                if (completed is AppResult.Failed) return completed
                return response
            }
        }

    val history =
        object : CollaborationHistoryRepository {
            override suspend fun getEventFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> {
                scope(access, conversationId, roomId)
                return read("original:$eventId") { originals[eventId] }
            }

            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> =
                error("No presentation or full-history scan")

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("Only indexed retained identities")

            override suspend fun getByLogicalId(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No outgoing receipt recovery")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("Reconciliation cannot rewrite retained content")
        }

    val edits =
        object : CollaborationMessageEditRepository {
            override suspend fun getReplacementFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationReplacementEventFact?> {
                scope(access, conversationId, roomId)
                return read("replacement:$eventId") { replacements[eventId] }
            }

            override suspend fun mergeEdits(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                edits: List<CollaborationMessageEdit>,
            ): AppResult<Boolean> = error("Reconciliation cannot write replacement content")
        }

    val reconcile =
        ReconcileCollaborationSpaceOriginalHistoryReferences(
            pending.transition.demands,
            provider.metadata,
            provider.registrations,
            traversal,
            history,
            edits,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
        )

    fun retainOriginal(reference: CollaborationHistoryReference = references.events.first()) {
        val event = reference.event
        originals[event.eventId] =
            CollaborationHistoryEventFact(event.eventId, event.sender, event.timestamp, false)
    }

    fun retainReplacement(reference: CollaborationHistoryReference = references.events.last()) {
        val event = reference.event
        replacements[event.eventId] =
            CollaborationReplacementEventFact(event.eventId, event.sender, event.timestamp, false)
    }

    suspend fun run() = reconcile(runtime, pending.room, references)

    private fun scope(access: CollaborationLocalAccess, conversationId: String, roomId: String) {
        assertEquals(provider.companyAccess, access)
        assertEquals(provider.traversal.conversationId, conversationId)
        assertEquals(provider.traversal.roomId, roomId)
    }
}
