package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.boundedCollaborationHistoryRecoveryReferences
import dev.fajar.starter.collaboration.domain.policies.collaborationHistoryUntilPosition
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlinx.coroutines.flow.first

/** Both typed recovery APIs exercise the same actual database with their own namespace. */
internal class CollaborationHistoryRecoveryFixture(
    owner: CollaborationHistoryDatabaseOwner,
    val space: Boolean,
) {
    val local = CollaborationHistoryFixture(owner)
    var sessionId = local.access.sessionId
    val access
        get() = local.access.copy(sessionId = sessionId)

    val spaceAccess
        get() =
            CollaborationSpaceLocalAccess(
                access.sessionId,
                access.audience,
                access.accountId,
                access.companyId,
            )

    val traversal =
        CollaborationHistoryTraversal(
            local.conversation,
            local.room,
            historyId(9100),
            CollaborationHistoryPurpose.Incoming,
        )
    val provider = CollaborationHistoryProviderIdentity(historyId(9300), historyId(9301))
    val coordinates
        get() =
            if (space) CollaborationHistoryCoordinates.Space(spaceAccess, traversal.conversationId)
            else CollaborationHistoryCoordinates.Company(access, traversal.conversationId)

    val runtime
        get() =
            CollaborationSpaceRuntimeReady(
                CollaborationSpaceOwner(
                    CollaborationNativeParent(
                        access.sessionId,
                        access.audience,
                        access.accountId,
                        historyId(9401),
                    ),
                    spaceAccess.spaceId,
                ),
                traversal.deviceId,
                historyId(9500),
            )

    fun company(store: ReplicaStore = local.database.replicas) =
        EncryptedCollaborationHistoryTraversalRepository(store, local.cipher, local.keys)

    fun spaces(store: ReplicaStore = local.database.replicas) =
        EncryptedCollaborationSpaceHistoryTraversalRepository(store, local.cipher, local.keys)

    fun repository(
        store: ReplicaStore = local.database.replicas,
        credentials: AtomicCredentialStore = local.keys,
    ) = EncryptedCollaborationHistoryRecoveryRepository(store, local.cipher, credentials)

    fun event(index: Int = 1) =
        CollaborationProviderHistoryEvent(
            "\$reference-$index",
            "@fictional:example.test",
            200_000L - index,
            "m.room.encrypted",
        )

    fun recovery(through: Long = 3) = CollaborationHistoryRecoveryPosition(historyId(9800), through)

    suspend fun replace(
        expected: CollaborationHistoryProgress,
        recovery: CollaborationHistoryRecoveryPosition,
        store: ReplicaStore = local.database.replicas,
        credentials: AtomicCredentialStore = local.keys,
    ) =
        if (space)
            repository(store, credentials).replace(spaceAccess, traversal, expected, recovery)
        else repository(store, credentials).replace(access, traversal, expected, recovery)

    suspend fun progress(): CollaborationHistoryProgress? =
        success(
            if (space) spaces().progress(spaceAccess, traversal)
            else company().progress(access, traversal)
        )

    suspend fun stage(
        events: List<CollaborationProviderHistoryEvent> = (1..3).map(::event),
        next: String? = "next",
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition,
    ) {
        val previous = progress()
        val position = if (origin == CollaborationHistoryPageOrigin.Latest) null else previous?.next
        val owner =
            CollaborationOwner(
                access.sessionId,
                access.audience,
                access.accountId,
                if (space) provider.companyId else access.companyId,
                historyId(9400),
            )
        val page =
            CollaborationProviderHistoryPage(
                owner,
                if (space) provider.deviceId else traversal.deviceId,
                traversal.roomId,
                position,
                position ?: "head",
                next,
                events,
                collaborationHistoryUntilPosition(traversal, previous, origin),
            )
        assertEquals(
            AppResult.Success(true),
            if (space)
                spaces()
                    .stage(
                        spaceAccess,
                        traversal,
                        previous,
                        CollaborationSpaceProviderHistoryPage(runtime, page),
                        origin,
                    )
            else company().stage(access, traversal, previous, page, origin),
        )
    }

    suspend fun read(after: String? = null, limit: Int = 20) =
        success(
            if (space) spaces().observeReferences(spaceAccess, traversal, after, limit).first()
            else company().observeReferences(access, traversal, after, limit).first()
        )

    suspend fun resolve(page: CollaborationHistoryReferences) =
        if (space) spaces().resolve(spaceAccess, traversal, page.progress!!, page.events)
        else company().resolve(access, traversal, page.progress!!, page.events)

    suspend fun advance(
        page: CollaborationHistoryReferences,
        retry: Boolean = true,
        limit: Int = 1,
    ) =
        if (space) spaces().advanceIncomingReferences(spaceAccess, traversal, page, retry, limit)
        else company().advanceIncomingReferences(access, traversal, page, retry, limit)

    suspend fun recoveryPage(limit: Int = 16) =
        requireNotNull(
            boundedCollaborationHistoryRecoveryReferences(
                read(progress()!!.recovery!!.after, limit),
                limit,
            )
        )

    suspend fun advanceRecovery(
        page: CollaborationHistoryReferences,
        unresolved: Int = page.events.size,
        limit: Int = 16,
        store: ReplicaStore = local.database.replicas,
    ) =
        if (space) repository(store).advance(spaceAccess, traversal, page, unresolved, limit)
        else repository(store).advance(access, traversal, page, unresolved, limit)

    suspend fun raw() =
        local.database.replicas
            .observe(coordinates.scope, coordinates.traversalPartition(traversal), limit = 200)
            .first()

    fun <T> success(value: AppResult<T>): T = assertIs<AppResult.Success<T>>(value).value
}
