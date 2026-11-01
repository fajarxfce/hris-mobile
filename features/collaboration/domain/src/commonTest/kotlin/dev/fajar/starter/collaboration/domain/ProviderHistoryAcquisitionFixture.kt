package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationProviderHistory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.Flow

internal class ProviderHistoryAcquisitionFixture {
    val delivery = CollaborationDeliveryFixture()
    val local = delivery.base
    val traversal =
        CollaborationHistoryTraversal(
            delivery.conversation,
            delivery.device.roomId,
            delivery.device.deviceId,
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
    val stagedOrigins = mutableListOf<CollaborationHistoryPageOrigin>()
    val stagedProgress = mutableListOf<CollaborationHistoryProgress?>()

    init {
        delivery.control.beforeRemote = { admissions += it }
    }

    private val runtime =
        object : CollaborationRuntimeRepository by delivery.runtimes {
            override suspend fun readProviderHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                position: String?,
            ): AppResult<CollaborationProviderHistoryPage> {
                assertEquals(delivery.owner, owner)
                assertEquals(traversal.deviceId, deviceId)
                assertEquals(traversal.roomId, matrixRoomId)
                positions += position
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
            delivery.control.remote,
            local.setup.remote,
            runtime,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    suspend fun acquire(
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition
    ) = useCase("selection", traversal.deviceId, traversal.conversationId, origin)

    companion object {
        const val generation = "b1000000-0000-4000-8000-000000000001"
    }
}
