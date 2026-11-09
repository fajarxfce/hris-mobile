package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationProviderHistory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.Flow

internal class ProviderHistoryAcquisitionFixture(
    purpose: CollaborationHistoryPurpose = CollaborationHistoryPurpose.Browse
) {
    val delivery = CollaborationDeliveryFixture()
    val local = delivery.base
    val traversal =
        CollaborationHistoryTraversal(
            delivery.conversation,
            delivery.device.roomId,
            delivery.device.deviceId,
            purpose,
        )
    val admissions = mutableListOf<String>()
    var page =
        CollaborationProviderHistoryPage(
            delivery.owner,
            traversal.deviceId,
            traversal.roomId,
            null,
            "start",
            "next",
            List(20) {
                CollaborationProviderHistoryEvent(
                    "\$event-$it",
                    "@fictional:test",
                    it.toLong(),
                    "m.room.encrypted",
                )
            },
        )
    var progress: CollaborationHistoryProgress? = null
    var readFailure: Failure? = null
    var pageFailure: Failure? = null
    var stageFailure: Failure? = null
    var rejectStage = false
    var reads = 0
    var active = 0
    var stages = 0
    var beforeProgressReturns: suspend () -> Unit = {}
    var beforePageReturns: suspend () -> Unit = {}
    var afterStage: suspend () -> Unit = {}
    val saved = mutableListOf<CollaborationProviderHistoryPage>()
    val positions = mutableListOf<String?>()
    val stops = mutableListOf<String?>()
    val stagedOrigins = mutableListOf<CollaborationHistoryPageOrigin>()
    val stagedProgress = mutableListOf<CollaborationHistoryProgress?>()

    init {
        delivery.control.beforeRemote = { admissions += it }
    }

    private val runtime =
        object : CollaborationRuntimeRepository by delivery.runtimes {
            override suspend fun selfIdentity(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationSelfIdentity?
            > = error("No implicit identity acquisition")

            override suspend fun prepareHistoryReopen(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<Boolean> = error("No implicit history reopening")

            override suspend fun readProviderHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                position: String?,
                untilPosition: String?,
            ): AppResult<CollaborationProviderHistoryPage> {
                assertEquals(delivery.owner, owner)
                assertEquals(traversal.deviceId, deviceId)
                assertEquals(traversal.roomId, matrixRoomId)
                positions += position
                stops += untilPosition
                reads++
                active++
                try {
                    val captured = page
                    beforePageReturns()
                    return pageFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
                } finally {
                    active--
                }
            }
        }

    private val history =
        object : CollaborationHistoryTraversalRepository {
            override suspend fun advanceIncomingReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                source: CollaborationHistoryReferences,
                retryRequired: Boolean,
            ): AppResult<Boolean> = error("No implicit incoming seek advance")

            override suspend fun progress(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> {
                checkScope(access, traversal)
                val captured = progress
                beforeProgressReturns()
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
            }

            override fun observeReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationHistoryReferences>> =
                error("Acquisition does not open a reference observer")

            override suspend fun resolve(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> = error("No implicit reconciliation")

            override suspend fun stage(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress?,
                page: CollaborationProviderHistoryPage,
                origin: CollaborationHistoryPageOrigin,
            ): AppResult<Boolean> {
                checkScope(access, traversal)
                assertEquals(delivery.owner, page.owner)
                stages++
                stagedOrigins += origin
                stagedProgress += expected
                if (rejectStage || expected != progress) return AppResult.Success(false)
                saved += page
                progress =
                    CollaborationHistoryProgress(
                        expected?.generation ?: generation,
                        (expected?.revision ?: -1) + 1,
                        page.next,
                        (expected?.references ?: 0) + page.events.size,
                        collaborationIncomingHistoryPosition(traversal, expected, page, origin),
                    )
                afterStage()
                return stageFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }

    private fun checkScope(access: CollaborationLocalAccess, key: CollaborationHistoryTraversal) {
        assertEquals(delivery.owner.localListAccess(), access)
        assertEquals(traversal, key)
    }

    val useCase =
        AcquireCollaborationProviderHistory(
            history,
            delivery.lists,
            delivery.transport,
            runtime,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    suspend fun acquire(
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition
    ) =
        useCase(
            "selection",
            traversal.deviceId,
            traversal.conversationId,
            origin,
            traversal.purpose,
        )

    companion object {
        const val generation = "b1000000-0000-4000-8000-000000000001"
    }
}
