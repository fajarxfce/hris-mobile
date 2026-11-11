package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationIncomingHistoryLocatorRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import kotlin.test.*

internal class CollaborationIncomingHistoryLocatorFixture(
    owner: CollaborationHistoryDatabaseOwner
) {
    val local = CollaborationHistoryFixture(owner)
    val access
        get() = local.access

    val space =
        CollaborationSpaceLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            access.companyId,
        )
    val scope
        get() = historyScope(access)

    private var generation = 100_000

    fun prefix(space: Boolean = false, owner: String = access.companyId) =
        COLLABORATION_HISTORY_PREFIX + (if (space) "space/v1/" else "") + owner + "/"

    fun partition(
        index: Int,
        lane: String? = "incoming-pages",
        device: Int = 99,
        space: Boolean = false,
    ) = prefix(space) + historyId(index) + if (lane == null) "" else "/$lane/" + historyId(device)

    fun repository(store: ReplicaStore = local.database.replicas) =
        StoredCollaborationIncomingHistoryLocatorRepository(store)

    suspend fun publish(partition: String): ReplicaCheckpoint {
        val store = local.database.replicas
        assertTrue(
            store.replace(
                scope,
                partition,
                store.checkpoint(scope, partition),
                ReplicaSnapshot(
                    historyId(generation++),
                    "opaque checkpoint; locator must not decrypt",
                    emptyList(),
                ),
            )
        )
        return assertNotNull(store.checkpoint(scope, partition))
    }

    suspend fun read(
        after: String? = null,
        limit: Int = 16,
        space: Boolean = false,
    ): CollaborationIncomingHistoryLocatorPage {
        val result =
            if (space) repository().read(this.space, after, limit)
            else repository().read(access, after, limit)
        return assertIs<AppResult.Success<CollaborationIncomingHistoryLocatorPage>>(result).value
    }
}
