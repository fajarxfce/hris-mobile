package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationIncomingHistoryPosition
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationSpaceOriginalProviderHistory
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

internal class SpaceOriginalProviderHistoryFixture {
    val base =
        SpaceOriginalIncomingLoadFixture().also {
            it.captured = it.captured.copy(phase = CollaborationSpaceIncomingPhase.Latest)
            it.demand(requireNotNull(it.snapshot).demand.copy(rooms = listOf(it.captured)))
        }
    val runtime = base.runtime
    val access = base.access
    val companyAccess =
        CollaborationLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            base.position.companyId,
        )
    val traversal =
        CollaborationHistoryTraversal(
            base.position.conversationId,
            base.room.roomId,
            base.setup.sdkDevice,
            CollaborationHistoryPurpose.Incoming,
        )
    var request =
        CollaborationSpaceOriginalIncomingRoom(
            base.captured,
            CollaborationSpaceOriginalConversation(base.owner, base.original, base.room),
            traversal,
        )
    val physical = base.setup.sdkOwner
    var page =
        CollaborationSpaceProviderHistoryPage(
            runtime,
            CollaborationProviderHistoryPage(
                physical,
                traversal.deviceId,
                traversal.roomId,
                null,
                "start",
                "next",
                List(2) {
                    CollaborationProviderHistoryEvent(
                        "\$event-$it",
                        base.source.matrixUserId,
                        it.toLong(),
                        "m.room.encrypted",
                    )
                },
            ),
        )
    var progress: CollaborationHistoryProgress? = null
    val references = linkedMapOf<String, CollaborationHistoryReference>()
    var open = true
    var commit = true
    var stageResponse: AppResult<Boolean> = AppResult.Success(true)
    val calls = mutableListOf<String>()
    val requests = mutableListOf<Pair<String?, String?>>()
    val saved = mutableListOf<CollaborationProviderHistoryPage>()
    val sourceReads = mutableListOf<String>()
    val hooks = mutableMapOf<String, suspend () -> Unit>()
    val failures = mutableMapOf<String, Failure>()
    val sourceResponses = mutableMapOf<String, CollaborationHistorySource>()
    var closes = 0

    private suspend fun <T> read(stage: String, value: () -> T): AppResult<T> {
        calls += stage
        hooks[stage]?.invoke()
        return failures[stage]?.let { AppResult.Failed(it) } ?: AppResult.Success(value())
    }

    val demands =
        object : CollaborationSpaceIncomingDemandRepository by base.demands {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@SpaceOriginalProviderHistoryFixture.access, access)
                return read("demand") { base.snapshot }
            }
        }
    val metadata =
        object : CollaborationSpaceOriginalRoomMetadataRepository by base.metadata {
            override suspend fun getByConversation(
                access: CollaborationSpaceLocalAccess,
                position: CollaborationOriginalConversationPosition,
            ): AppResult<CollaborationSpaceOriginalRoomMetadata?> {
                assertEquals(this@SpaceOriginalProviderHistoryFixture.access, access)
                assertEquals(base.position, position)
                return read("metadata") { base.retained }
            }
        }
    val registrations =
        object : CollaborationSpaceRegistrationCommandRepository by base.registrations {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): AppResult<CollaborationSpaceRegistrationRecord?> {
                assertEquals(this@SpaceOriginalProviderHistoryFixture.access, access)
                assertEquals(runtime.owner.parent.nativeSessionId, nativeSessionId)
                return read("registration") { base.registration }
            }
        }
    val runtimes =
        object : CollaborationSpaceRuntimeRepository {
            override suspend fun acquireOriginalQueue(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
            ): AppResult<CollaborationSpaceOriginalQueueLease> = error("No outgoing queue")

            override suspend fun inspectOriginalQueue(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
            ): AppResult<CollaborationSpaceOriginalQueueSnapshot> =
                error("No outgoing queue inspection")

            override suspend fun installOriginal(
                owner: CollaborationSpaceOwner,
                original: CollaborationExistingRuntimeHandover,
                setup: CollaborationSpaceSetupRecord,
            ): AppResult<CollaborationSpaceRuntimeLease> = error("No replacement native client")

            override suspend fun isSpaceOpen(
                owner: CollaborationSpaceOwner,
                deviceId: String,
                operationId: String,
            ): AppResult<Boolean> {
                assertEquals(runtime.owner, owner)
                assertEquals(runtime.deviceId, deviceId)
                assertEquals(runtime.operationId, operationId)
                return read("runtime") { open }
            }

            override suspend fun closeSpace(owner: CollaborationSpaceOwner): AppResult<Unit> {
                assertEquals(runtime.owner, owner)
                closes++
                open = false
                return read("close") { Unit }
            }
        }
    val provider =
        object : CollaborationSpaceProviderHistoryRepository {
            override suspend fun readPage(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
                position: String?,
                untilPosition: String?,
            ): AppResult<CollaborationSpaceProviderHistoryPage> {
                assertEquals(this@SpaceOriginalProviderHistoryFixture.runtime, runtime)
                assertEquals(traversal.roomId, matrixRoomId)
                requests += position to untilPosition
                return read("page") { page }
            }
        }
    val history =
        object : CollaborationHistoryTraversalRepository {
            override suspend fun progress(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> {
                scope(access, traversal)
                return read("progress") { progress }
            }

            override suspend fun source(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                eventId: String,
            ): AppResult<CollaborationHistorySource> {
                scope(access, traversal)
                sourceReads += eventId
                return read("source:$eventId") {
                    sourceResponses[eventId]
                        ?: CollaborationHistorySource(progress, references[eventId])
                }
            }

            override fun observeReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationHistoryReferences>> =
                error("No pending pass or full scan")

            override suspend fun resolve(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> = error("No content acknowledgement")

            override suspend fun advanceIncomingReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                source: CollaborationHistoryReferences,
                retryRequired: Boolean,
            ): AppResult<Boolean> = error("No pending seek")

            override suspend fun stage(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress?,
                page: CollaborationProviderHistoryPage,
                origin: CollaborationHistoryPageOrigin,
            ): AppResult<Boolean> {
                scope(access, traversal)
                val entered = read("stage") { Unit }
                if (entered is AppResult.Failed) return entered
                saved += page
                if (expected != progress) return AppResult.Success(false)
                if (commit) {
                    var count = expected?.references ?: 0L
                    for (event in page.events) if (event.eventId !in references) {
                        references[event.eventId] = CollaborationHistoryReference(count++, event)
                    }
                    progress =
                        CollaborationHistoryProgress(
                            expected?.generation ?: base.id(100),
                            expected?.revision?.plus(1) ?: 0,
                            page.next,
                            count,
                            collaborationIncomingHistoryPosition(traversal, expected, page, origin),
                        )
                }
                val completed = read("committed") { Unit }
                return if (completed is AppResult.Failed) completed else stageResponse
            }
        }

    private fun scope(access: CollaborationLocalAccess, traversal: CollaborationHistoryTraversal) {
        assertEquals(companyAccess, access)
        assertEquals(this.traversal, traversal)
    }

    val acquire =
        AcquireCollaborationSpaceOriginalProviderHistory(
            demands,
            metadata,
            registrations,
            base.sync,
            runtimes,
            provider,
            history,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.native.clock,
            base.native.time,
        )

    suspend fun run() = acquire(runtime, request)

    fun phase(value: CollaborationSpaceIncomingPhase) {
        base.captured = base.captured.copy(phase = value)
        base.demand(requireNotNull(base.snapshot).demand.copy(rooms = listOf(base.captured)))
        request = request.copy(captured = base.captured)
    }
}
