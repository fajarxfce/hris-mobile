package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationSpaceProviderHistory
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

internal class SpaceProviderHistoryFixture(
    val purpose: CollaborationHistoryPurpose = CollaborationHistoryPurpose.Incoming
) {
    val base = SpaceOriginalDeliveryFixture()
    val native = base.native
    val queue = base.queue
    val runtime = queue.runtime
    val conversation = base.model.conversation
    val access = runtime.owner.localDirectoryAccess()
    val traversal =
        CollaborationHistoryTraversal(
            conversation.id,
            base.model.room.roomId,
            runtime.deviceId,
            purpose,
        )
    val physical =
        CollaborationOwner(
            runtime.owner.parent.sessionId,
            queue.binding.audience,
            queue.binding.accountId,
            queue.binding.companyId,
            queue.binding.nativeSessionId,
        )
    val identity = CollaborationHistoryProviderIdentity(physical.companyId, queue.device.deviceId)
    var page =
        CollaborationSpaceProviderHistoryPage(
            runtime,
            CollaborationProviderHistoryPage(
                physical,
                queue.device.deviceId,
                traversal.roomId,
                null,
                "start",
                "next",
                List(20) {
                    CollaborationProviderHistoryEvent(
                        "\$event-$it",
                        "@fictional:example.test",
                        it.toLong(),
                        "m.room.encrypted",
                    )
                },
            ),
        )
    var progress: CollaborationHistoryProgress? = null
    var afterCommit = checkpoint()
    var commit = true
    var response: AppResult<Boolean> = AppResult.Success(true)
    val requests = mutableListOf<Pair<String?, String?>>()
    val saved = mutableListOf<CollaborationSpaceProviderHistoryPage>()
    val origins = mutableListOf<CollaborationHistoryPageOrigin>()
    val expected = mutableListOf<CollaborationHistoryProgress?>()

    val provider =
        object : CollaborationSpaceProviderHistoryRepository {
            override suspend fun readPage(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
                position: String?,
                untilPosition: String?,
            ): AppResult<CollaborationSpaceProviderHistoryPage> {
                assertEquals(this@SpaceProviderHistoryFixture.runtime, runtime)
                assertEquals(traversal.roomId, matrixRoomId)
                requests += position to untilPosition
                return base.read("page") { page }
            }
        }
    val history =
        object : CollaborationSpaceHistoryTraversalRepository {
            override suspend fun progress(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> {
                scope(access, traversal)
                return base.read("progress") { progress }
            }

            override suspend fun stage(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress?,
                page: CollaborationSpaceProviderHistoryPage,
                origin: CollaborationHistoryPageOrigin,
            ): AppResult<Boolean> {
                scope(access, traversal)
                val entered = base.read("stage") { Unit }
                if (entered is AppResult.Failed) return entered
                this@SpaceProviderHistoryFixture.expected += expected
                saved += page
                origins += origin
                if (expected != progress) return AppResult.Success(false)
                if (commit) progress = afterCommit
                val replied = base.read("committed") { Unit }
                return if (replied is AppResult.Failed) replied else response
            }

            override fun observeReferences(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationHistoryReferences>> = error("No content or pending pass")

            override suspend fun resolve(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> = error("No content acknowledgement")

            override suspend fun advanceIncomingReferences(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                source: CollaborationHistoryReferences,
                retryRequired: Boolean,
            ): AppResult<Boolean> = error("No pending seek")
        }
    val useCase =
        AcquireCollaborationSpaceProviderHistory(
            base.bindings,
            base.transport,
            base.runtimes,
            provider,
            history,
            base.directory.directory,
            native.setup.sessions,
            native.server,
            AppBuild(AppPlatform.Android, 5, "test", null),
            native.clock,
            native.time,
        )

    suspend fun acquire(
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition
    ) = useCase(runtime, conversation, origin, purpose)

    fun checkpoint(next: String? = "next") =
        CollaborationHistoryProgress(
            queue.id(950),
            0,
            next,
            20,
            if (purpose == CollaborationHistoryPurpose.Incoming)
                CollaborationIncomingHistoryPosition("start", null)
            else null,
            identity,
        )

    private fun scope(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
    ) {
        assertEquals(this.access, access)
        assertEquals(this.traversal, traversal)
    }
}
