package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationHistoryTraversalRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.flow.first

internal class CollaborationHistoryTraversalFixture(
    databaseOwner: CollaborationHistoryDatabaseOwner,
    purpose: CollaborationHistoryPurpose = CollaborationHistoryPurpose.Browse,
) {
    val local = CollaborationHistoryFixture(databaseOwner)
    val access
        get() = local.access

    val traversal =
        CollaborationHistoryTraversal(local.conversation, local.room, historyId(9100), purpose)
    val owner =
        CollaborationOwner(
            access.sessionId,
            access.audience,
            access.accountId,
            access.companyId,
            historyId(9101),
        )

    fun repository(store: ReplicaStore = local.database.replicas) =
        EncryptedCollaborationHistoryTraversalRepository(store, local.cipher, local.keys)

    fun event(i: Int = 1) =
        CollaborationProviderHistoryEvent(
            "\$reference-$i",
            "@fictional:example.test",
            200_000L - i,
            "m.room.encrypted",
        )

    fun page(
        events: List<CollaborationProviderHistoryEvent>,
        position: String? = null,
        next: String? = "next-1",
    ) =
        CollaborationProviderHistoryPage(
            owner,
            traversal.deviceId,
            traversal.roomId,
            position,
            position ?: "initial-position",
            next,
            events,
        )

    suspend fun progress(access: CollaborationLocalAccess = this.access) =
        success(repository().progress(access, traversal))

    suspend fun read(
        access: CollaborationLocalAccess = this.access,
        after: String? = null,
        limit: Int = 50,
    ) = success(repository().observeReferences(access, traversal, after, limit).first())

    suspend fun stage(events: List<CollaborationProviderHistoryEvent>, next: String? = "next-1") {
        val previous = progress()
        assertTrue(
            success(
                repository().stage(access, traversal, previous, page(events, previous?.next, next))
            )
        )
    }

    suspend fun raw() =
        local.database.replicas
            .observe(historyScope(access), traversalPartition(access, traversal), limit = 200)
            .first()

    fun <T> success(result: AppResult<T>): T = assertIs<AppResult.Success<T>>(result).value
}
