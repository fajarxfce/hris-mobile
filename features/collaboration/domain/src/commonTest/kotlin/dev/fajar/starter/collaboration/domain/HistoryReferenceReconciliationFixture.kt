package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationHistoryReferences
import dev.fajar.starter.collaboration.domain.usecases.ReconcileCollaborationHistoryReferences
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.*

internal class HistoryReferenceReconciliationFixture(
    purpose: CollaborationHistoryPurpose = CollaborationHistoryPurpose.Browse
) {
    val local = MessageIntentFixture()
    val key =
        CollaborationHistoryTraversal(local.summary!!.conversation.id, "!room:test", id, purpose)
    var progress = CollaborationHistoryProgress(id, 4, "provider-next", 3)
    var references =
        List(3) { i ->
            CollaborationHistoryReference(
                i.toLong(),
                CollaborationProviderHistoryEvent(
                    "\$event-$i",
                    "@fictional:test",
                    100L + i,
                    "m.room.encrypted",
                ),
            )
        }
    val facts =
        references
            .take(2)
            .associate { reference ->
                val event = reference.event
                event.eventId to
                    CollaborationHistoryEventFact(
                        event.eventId,
                        event.sender,
                        if (reference.sequence == 1L) null else event.timestamp,
                        reference.sequence == 1L,
                    )
            }
            .toMutableMap()
    var pageOverride: CollaborationHistoryReferences? = null
    var readFailure: Failure? = null
    var factFailure: Failure? = null
    var writeFailure: Failure? = null
    var rejectWrite = false
    var reads = 0
    var factReads = 0
    var writes = 0
    var activeReads = 0
    val accessed = mutableListOf<CollaborationLocalAccess>()
    var afterRead: suspend () -> Unit = {}
    var afterFact: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}

    private val traversal =
        object : CollaborationHistoryTraversalRepository {
            override suspend fun progress(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> =
                error("Use the coherent reference snapshot")

            override fun observeReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ) = flow {
                accessed += access
                assertEquals(key, traversal)
                reads++
                activeReads++
                try {
                    val pending =
                        references.filter {
                            !it.resolved &&
                                (after == null ||
                                    it.sequence > after.removePrefix("after-").toLong())
                        }
                    val captured =
                        pageOverride
                            ?: CollaborationHistoryReferences(
                                progress,
                                pending.take(limit),
                                if (pending.size > limit) "after-${pending[limit - 1].sequence}"
                                else null,
                            )
                    afterRead()
                    emit(readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured))
                } finally {
                    activeReads--
                }
            }

            override suspend fun stage(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress?,
                page: CollaborationProviderHistoryPage,
                origin: CollaborationHistoryPageOrigin,
            ): AppResult<Boolean> = error("Offline reconciliation never fetches pages")

            override suspend fun resolve(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> {
                accessed += access
                assertEquals(key, traversal)
                writes++
                if (rejectWrite || progress != expected) return AppResult.Success(false)
                assertTrue(
                    references.all {
                        it in this@HistoryReferenceReconciliationFixture.references && !it.resolved
                    }
                )
                this@HistoryReferenceReconciliationFixture.references =
                    this@HistoryReferenceReconciliationFixture.references.map {
                        if (it in references) it.copy(resolved = true) else it
                    }
                progress = progress.copy(revision = progress.revision + 1)
                afterWrite()
                return writeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }
    private val history =
        object : CollaborationHistoryRepository {
            override suspend fun getEventFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> {
                accessed += access
                assertEquals(key.conversationId, conversationId)
                assertEquals(key.roomId, roomId)
                factReads++
                val captured = facts[eventId]
                afterFact()
                return factFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
            }

            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> = error("No full history scan")

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> =
                error("Canonical timestamp is not an alias timestamp")

            override suspend fun getByLogicalId(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No guessing")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("No invented originals")
        }
    val useCase =
        ReconcileCollaborationHistoryReferences(
            traversal,
            history,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    val reader =
        ReadCollaborationHistoryReferences(
            traversal,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    suspend fun read(after: String? = null, limit: Int = 20) =
        reader("selection", key, after, limit)

    suspend fun reconcile(after: String? = null, limit: Int = 20) =
        useCase("selection", key, after, limit)

    companion object {
        const val id = "c4000000-0000-4000-8000-000000000001"
    }
}
