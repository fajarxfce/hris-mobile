package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceKeyReferences
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class SpaceKeyReferenceReadFixture {
    val original = SpaceOriginalKeyReferenceReadFixture()
    val base = original.base
    val provider = original.provider
    val generation = original.generation
    val conversationId = base.id(940)
    var binding: CollaborationSpaceRoomBinding? =
        CollaborationSpaceRoomBinding(
            CollaborationSpaceConversationMetadata(
                base.access.spaceId,
                conversationId,
                base.original.firstAccountId,
                base.original.secondAccountId,
                base.original.createdBy,
                base.original.createdAt,
                0,
            ),
            CollaborationSpacePublishedRoom(
                base.room.roomId,
                base.room.serverName,
                CollaborationSpaceRoomOrigin.SPACE,
                base.room.boundAt + 1.days,
            ),
        )
    var keyHistory =
        CollaborationSpaceKeyHistory(
            base.source,
            requireNotNull(binding),
            original.keyHistory.traversal.copy(conversationId = conversationId),
        )
    var progress =
        original.progress?.copy(
            provider = CollaborationHistoryProviderIdentity(base.position.companyId, base.id(951))
        )
    var events = List(2, original::reference)
    var after: String? = null
    var page: CollaborationHistoryReferences? = null
    var emptyFlow = false
    var commit = true
    var response: AppResult<Boolean> = AppResult.Success(true)
    var progressReads = 0
    var writes = 0
    val requests = mutableListOf<Pair<String?, Int>>()
    val hooks = mutableMapOf<String, suspend () -> Unit>()
    val failures = mutableMapOf<String, Failure>()
    val calls = mutableListOf<String>()

    init {
        base.retained = null
    }

    suspend fun touch(name: String) {
        calls += name
        hooks[name]?.invoke()
    }

    fun scope(access: CollaborationSpaceLocalAccess, traversal: CollaborationHistoryTraversal) {
        assertEquals(base.access, access)
        assertEquals(keyHistory.traversal, traversal)
        assertNotEquals(base.position.conversationId, traversal.conversationId)
    }

    fun originalAlias() {
        val current = requireNotNull(binding)
        binding =
            current.copy(
                room =
                    current.room.copy(
                        origin = CollaborationSpaceRoomOrigin.LEGACY,
                        original =
                            CollaborationSpaceOriginalRoom(
                                base.position,
                                base.room.roomId,
                                base.room.boundAt,
                            ),
                    ),
                original = CollaborationSpaceOriginalRoomMetadata(base.original, base.room),
            )
        keyHistory = keyHistory.copy(binding = requireNotNull(binding))
    }

    val bindings =
        object : CollaborationSpaceRoomBindingRepository {
            override fun observeByConversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): Flow<AppResult<CollaborationSpaceRoomBinding?>> =
                error("Binding observation is not part of this fixture")

            override suspend fun getByConversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> {
                assertEquals(base.access, access)
                assertEquals(this@SpaceKeyReferenceReadFixture.conversationId, conversationId)
                touch("binding")
                return failures["binding"]?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(binding)
            }

            override suspend fun findByRoom(
                access: CollaborationSpaceLocalAccess,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> = error("No room scan")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                binding: CollaborationSpaceRoomBinding,
            ): AppResult<Boolean> = error("No metadata writes")
        }
    val traversal =
        object : CollaborationSpaceHistoryTraversalRepository {
            override suspend fun progress(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> {
                scope(access, traversal)
                progressReads++
                touch("progress")
                return failures["progress"]?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(progress)
            }

            override fun observeReferences(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ) =
                flow<AppResult<CollaborationHistoryReferences>> {
                    scope(access, traversal)
                    assertEquals(16, limit)
                    requests += after to limit
                    touch("pending")
                    if (!emptyFlow)
                        emit(
                            failures["pending"]?.let { AppResult.Failed(it) }
                                ?: AppResult.Success(
                                    page
                                        ?: CollaborationHistoryReferences(
                                            progress,
                                            events,
                                            this@SpaceKeyReferenceReadFixture.after,
                                        )
                                )
                        )
                }

            override suspend fun source(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                eventId: String,
            ): AppResult<CollaborationHistorySource> = error("No per-event reads")

            override suspend fun stage(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress?,
                page: CollaborationSpaceProviderHistoryPage,
                origin: CollaborationHistoryPageOrigin,
            ): AppResult<Boolean> = error("No provider staging")

            override suspend fun resolve(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> = error("No reference acknowledgement")

            override suspend fun advanceIncomingReferences(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                source: CollaborationHistoryReferences,
                retryRequired: Boolean,
                limit: Int,
            ): AppResult<Boolean> = error("No primary seek")
        }
    val recovery =
        object : CollaborationHistoryRecoveryRepository by original.recovery {
            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                recovery: CollaborationHistoryRecoveryPosition,
            ): AppResult<Boolean> {
                scope(access, traversal)
                writes++
                touch("replace")
                val actual = progress
                if (
                    actual == null ||
                        actual.generation != expected.generation ||
                        actual.recovery != expected.recovery
                )
                    return AppResult.Success(false)
                if (commit)
                    progress = actual.copy(revision = actual.revision + 1, recovery = recovery)
                touch("replaced")
                return response
            }
        }
    val read =
        ReadCollaborationSpaceKeyReferences(
            bindings,
            provider.registrations,
            traversal,
            recovery,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
        )

    suspend fun run() = read(base.runtime, keyHistory, generation)

    suspend fun pending(): CollaborationHistoryReferences =
        assertIs<CollaborationHistoryRecoveryRead.Pending>(
                assertIs<AppResult.Success<*>>(run()).value
            )
            .references
}
