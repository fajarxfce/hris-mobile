package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationHistoryUntilPosition
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.flow.first

internal class CollaborationSpaceHistoryTraversalFixture(owner: CollaborationHistoryDatabaseOwner) {
    val local = CollaborationSpaceHistoryFixture(owner)
    val access
        get() = local.access

    val traversal =
        CollaborationHistoryTraversal(
            local.conversation,
            local.room,
            historyId(9100),
            CollaborationHistoryPurpose.Incoming,
        )
    val runtime =
        CollaborationSpaceRuntimeReady(
            CollaborationSpaceOwner(
                CollaborationNativeParent(
                    access.sessionId,
                    access.audience,
                    access.accountId,
                    historyId(9401),
                ),
                access.spaceId,
            ),
            traversal.deviceId,
            historyId(9500),
        )
    val physical =
        CollaborationOwner(
            access.sessionId,
            access.audience,
            access.accountId,
            historyId(9300),
            historyId(9400),
        )
    val provider = CollaborationHistoryProviderIdentity(physical.companyId, historyId(9200))

    fun repository(store: ReplicaStore = local.database.replicas) =
        EncryptedCollaborationSpaceHistoryTraversalRepository(store, local.cipher, local.keys)

    fun legacyRepository() =
        EncryptedCollaborationHistoryTraversalRepository(
            local.database.replicas,
            local.cipher,
            local.keys,
        )

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
        next: String? = if (position == null) "next-1" else "$position-next",
        until: String? = null,
        head: String = "first-head",
    ) =
        CollaborationSpaceProviderHistoryPage(
            runtime,
            CollaborationProviderHistoryPage(
                physical,
                provider.deviceId,
                traversal.roomId,
                position,
                position ?: head,
                next,
                events,
                until,
            ),
        )

    suspend fun progress() = success(repository().progress(access, traversal))

    suspend fun read(after: String? = null, limit: Int = 20) =
        success(repository().observeReferences(access, traversal, after, limit).first())

    suspend fun stage(
        events: List<CollaborationProviderHistoryEvent>,
        next: String? = "next-1",
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition,
        head: String = "first-head",
    ) {
        val previous = progress()
        val position = if (origin == CollaborationHistoryPageOrigin.Latest) null else previous?.next
        assertEquals(
            AppResult.Success(true),
            repository()
                .stage(
                    access,
                    traversal,
                    previous,
                    page(
                        events,
                        position,
                        next,
                        collaborationHistoryUntilPosition(traversal, previous, origin),
                        head,
                    ),
                    origin,
                ),
        )
    }

    suspend fun raw() =
        local.database.replicas
            .observe(spaceHistoryScope(access), traversalPartition(access, traversal), limit = 200)
            .first()

    fun <T> success(result: AppResult<T>): T = assertIs<AppResult.Success<T>>(result).value
}
