package dev.fajar.starter.collaboration.presentation.inbox

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*

/** Real use-case phases with an in-memory atomic replica; storage contracts run separately. */
internal class ChatInboxSyncFixture(private val base: ChatInboxFixture) {
    var failure: Failure? = null
    var current: CollaborationListSyncCheckpoint? = null
    private var facts = emptyMap<String, CollaborationListStoredFact>()
    var pending = false
    var commitWins = true
    var bootstrapRead: suspend (String?) -> AppResult<CollaborationSummaryBootstrap> = { cursor ->
        val from = cursor?.removePrefix("bootstrap-")?.toInt() ?: 0
        val source = base.records.value.sortedBy { it.conversation.id }
        val rows = source.drop(from).take(50)
        val next = (from + rows.size).takeIf { it < source.size }?.let { "bootstrap-$it" }
        AppResult.Success(
            CollaborationSummaryBootstrap(
                rows.map { CollaborationSummaryFact(it.conversation.id, 1, it) },
                next,
                "changes-0".takeIf { next == null },
                pending,
                base.now,
            )
        )
    }
    var changesRead: suspend (String) -> AppResult<CollaborationSummaryChanges> = { cursor ->
        AppResult.Success(
            CollaborationSummaryChanges(emptyList(), cursor, false, pending, base.now)
        )
    }
    val remote =
        object : CollaborationSummarySyncRepository {
            override suspend fun bootstrap(
                owner: CollaborationOwner,
                cursor: String?,
                limit: Int,
            ): AppResult<CollaborationSummaryBootstrap> {
                check(limit == 50)
                base.calls += cursor
                return failure?.let { AppResult.Failed(it) } ?: bootstrapRead(cursor)
            }

            override suspend fun changes(
                owner: CollaborationOwner,
                cursor: String,
                limit: Int,
            ): AppResult<CollaborationSummaryChanges> {
                check(limit == 50)
                base.calls += cursor
                return failure?.let { AppResult.Failed(it) } ?: changesRead(cursor)
            }
        }
    val cache =
        object : CollaborationListSyncReplicaRepository {
            override suspend fun syncWindow(access: CollaborationLocalAccess, ids: List<String>) =
                AppResult.Success(CollaborationListSyncWindow(current, ids.mapNotNull(facts::get)))

            override suspend fun beginSync(
                access: CollaborationLocalAccess,
                expected: CollaborationListSyncCheckpoint?,
                initial: CollaborationListSyncCheckpoint,
            ): AppResult<Boolean> {
                if (current != expected) return AppResult.Success(false)
                current = initial
                facts = emptyMap()
                return AppResult.Success(true)
            }

            override suspend fun commitSync(
                access: CollaborationLocalAccess,
                expected: CollaborationListSyncCheckpoint,
                next: CollaborationListSyncCheckpoint,
                changed: List<CollaborationSummaryFact>,
            ): AppResult<Boolean> {
                if (current != expected || !commitWins) return AppResult.Success(false)
                this@ChatInboxSyncFixture.facts +=
                    changed.associate {
                        it.conversationId to
                            CollaborationListStoredFact(it.conversationId, it.revision, it.summary)
                    }
                current = next
                if (next.published) {
                    base.records.value =
                        this@ChatInboxSyncFixture.facts.values
                            .mapNotNull { it.summary }
                            .sortedBy { it.conversation.id }
                    base.checkpoint.value = next.local
                }
                return AppResult.Success(true)
            }
        }
}
