package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceOriginalKeyReferences
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.flow

internal class SpaceOriginalKeyReferenceReadFixture {
    val transition = SpaceOriginalProviderTransitionFixture()
    val provider = transition.provider
    val base = transition.base
    val generation = base.id(980)
    val keyHistory =
        CollaborationSpaceOriginalKeyHistory(
            base.source,
            provider.request.conversation,
            provider.traversal.copy(deviceId = base.id(950)),
        )
    var progress: CollaborationHistoryProgress? = transition.checkpoint
    var events: List<CollaborationHistoryReference> = List(2) { reference(it) }
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
        base.snapshot = null
    }

    suspend fun touch(name: String) {
        calls += name
        hooks[name]?.invoke()
    }

    fun scope(access: CollaborationLocalAccess, traversal: CollaborationHistoryTraversal) {
        assertEquals(provider.companyAccess, access)
        assertEquals(keyHistory.traversal, traversal)
    }

    val traversal =
        object : CollaborationHistoryTraversalRepository by provider.history {
            override suspend fun progress(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> {
                scope(access, traversal)
                progressReads++
                touch("progress")
                return failures["progress"]?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(progress)
            }

            override fun observeReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ) =
                flow<AppResult<CollaborationHistoryReferences>> {
                    scope(access, traversal)
                    requests += after to limit
                    assertEquals(16, limit)
                    touch("pending")
                    if (!emptyFlow)
                        emit(
                            failures["pending"]?.let { AppResult.Failed(it) }
                                ?: AppResult.Success(
                                    page
                                        ?: CollaborationHistoryReferences(
                                            progress,
                                            events,
                                            this@SpaceOriginalKeyReferenceReadFixture.after,
                                        )
                                )
                        )
                }
        }

    val recovery =
        object : CollaborationHistoryRecoveryRepository {
            override suspend fun replace(
                access: CollaborationLocalAccess,
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

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                recovery: CollaborationHistoryRecoveryPosition,
            ): AppResult<Boolean> = error("Company history must not become Space history")

            override suspend fun advance(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                attempted: CollaborationHistoryReferences,
                unresolved: Int,
                limit: Int,
            ): AppResult<Boolean> = error("Reading cannot acknowledge a pass")

            override suspend fun advance(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                attempted: CollaborationHistoryReferences,
                unresolved: Int,
                limit: Int,
            ): AppResult<Boolean> = error("Reading cannot acknowledge a pass")
        }

    val read =
        ReadCollaborationSpaceOriginalKeyReferences(
            provider.metadata,
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

    fun reference(sequence: Int) =
        CollaborationHistoryReference(
            sequence.toLong(),
            CollaborationProviderHistoryEvent(
                "\$key-reference-$sequence",
                base.source.matrixUserId,
                sequence.toLong(),
                "m.room.encrypted",
            ),
        )
}
