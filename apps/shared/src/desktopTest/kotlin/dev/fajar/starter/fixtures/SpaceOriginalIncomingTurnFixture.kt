package dev.fajar.starter.fixtures

import dev.fajar.starter.app.work.CollaborationSpaceOriginalIncomingTurn
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Application sequencing only; owning domain and actual storage/native contracts are separate. */
internal class SpaceOriginalIncomingTurnFixture(
    scheduler: TestCoroutineScheduler,
    phase: CollaborationSpaceIncomingPhase = CollaborationSpaceIncomingPhase.Latest,
) {
    val worker = SpaceIncomingWorkerFixture(scheduler, 0)
    val runtime = worker.runtime
    val position = CollaborationOriginalConversationPosition(recoveryId(850), recoveryId(851))
    val captured =
        CollaborationSpaceIncomingRoomDemand(
            CollaborationSpaceIncomingTarget.Original(position),
            recoveryId(852),
            0,
            "$" + "head",
            phase,
            sourceId = worker.source.storeId,
        )
    val physicalDevice = recoveryId(853)
    val traversal =
        CollaborationHistoryTraversal(
            position.conversationId,
            "!original:example.test",
            physicalDevice,
            CollaborationHistoryPurpose.Incoming,
        )
    private val pair = listOf(runtime.owner.parent.accountId, recoveryId(854)).sorted()
    val conversation =
        CollaborationSpaceOriginalConversation(
            runtime.owner,
            CollaborationOriginalConversation(
                position,
                pair[0],
                pair[1],
                pair[0],
                worker.base.clock.now(),
                0,
            ),
            CollaborationOriginalRoom(
                "example.test",
                traversal.roomId,
                worker.base.clock.now(),
                pair[0],
            ),
        )
    val initialProgress =
        CollaborationHistoryProgress(
            recoveryId(855),
            0,
            null,
            2,
            CollaborationIncomingHistoryPosition("wave-start", null),
        )
    var progress: CollaborationHistoryProgress? = initialProgress
    val references =
        listOf("retained", "remote")
            .mapIndexed { index, name ->
                CollaborationHistoryReference(
                    index.toLong(),
                    CollaborationProviderHistoryEvent(
                        "$" + name,
                        "@peer:example.test",
                        index.toLong() + 1,
                        "m.room.encrypted",
                    ),
                )
            }
            .toMutableList()
    var localEventIds = setOf("$" + "retained")
    var providerMore = false
    var advanceResult = true
    var moreReferences = false
    var retryRequired = false
    var completeResult = true
    var recoveryResult = false
    var nullAt: String? = null
    var before: suspend (String) -> Unit = {}
    val failures = mutableMapOf<String, Failure>()
    val calls = mutableListOf<String>()
    val roomCaptures = mutableListOf<CollaborationSpaceIncomingRoomDemand>()
    val contentRequests = mutableListOf<List<String>>()
    val contentRooms = mutableListOf<CollaborationSpaceOriginalIncomingRoom>()
    val completionSources = mutableListOf<CollaborationHistoryReferences>()
    var referenceReads = 0
    var providerReads = 0
    val failure = Failure(FailureKind.Storage, "Fixture storage failure")
    var current: CollaborationSpaceIncomingRoomDemand?
        get() = worker.rooms.singleOrNull()
        set(value) {
            worker.rooms.clear()
            if (value != null) worker.rooms += value
        }

    init {
        current = captured
        worker.nextPosition = 1
    }

    private suspend fun step(name: String): AppResult.Failed? {
        calls += name
        before(name)
        return failures[name]?.let { AppResult.Failed(it) }
    }

    val turn =
        CollaborationSpaceOriginalIncomingTurn(
            room = { actual, requested ->
                assertEquals(runtime, actual)
                roomCaptures += requested
                val failed = step("room")
                if (failed != null) failed
                else {
                    val retained = current
                    if (
                        nullAt == "room" ||
                            retained == null ||
                            retained.generation != requested.generation
                    )
                        AppResult.Success(null)
                    else {
                        assertEquals(
                            retained,
                            requested,
                            "The caller must reload the exact persisted capture",
                        )
                        AppResult.Success(
                            CollaborationSpaceOriginalIncomingRoom(
                                requested,
                                conversation,
                                traversal,
                            )
                        )
                    }
                }
            },
            readDemand = { actual ->
                assertEquals(runtime, actual)
                val failed = step("demand")
                failed ?: AppResult.Success(worker.work(worker.source))
            },
            acquirePage = { actual, local ->
                assertEquals(runtime, actual)
                assertEquals(physicalDevice, local.traversal.deviceId)
                assertNotEquals(runtime.deviceId, local.traversal.deviceId)
                providerReads++
                val failed = step("provider")
                failed
                    ?: AppResult.Success(
                        CollaborationSpaceOriginalProviderHistoryAcquisition(
                            runtime,
                            local,
                            if (local.captured.phase == CollaborationSpaceIncomingPhase.Latest)
                                CollaborationHistoryPageOrigin.Latest
                            else CollaborationHistoryPageOrigin.SavedPosition,
                            progress,
                            initialProgress.copy(next = if (providerMore) "next-page" else null),
                            references.size,
                        )
                    )
            },
            advance = { acquired ->
                val failed = step("advance")
                if (failed != null) failed
                else {
                    if (advanceResult) {
                        current =
                            current?.copy(
                                phase =
                                    if (acquired.progress.exhausted)
                                        CollaborationSpaceIncomingPhase.Pending
                                    else CollaborationSpaceIncomingPhase.Provider,
                                retry = null,
                            )
                        progress = acquired.progress
                    }
                    val after = step("advanced")
                    after ?: AppResult.Success(advanceResult)
                }
            },
            readReferences = { actual, local ->
                assertEquals(runtime, actual)
                assertEquals(CollaborationSpaceIncomingPhase.Pending, local.captured.phase)
                referenceReads++
                val name = "references-$referenceReads"
                val failed = step(name)
                if (failed != null) failed
                else
                    AppResult.Success(
                        if (nullAt == name) null
                        else CollaborationHistoryReferences(progress, references.toList(), null)
                    )
            },
            reconcile = { actual, local, pending ->
                assertEquals(runtime, actual)
                assertEquals(physicalDevice, local.traversal.deviceId)
                val failed = step("reconcile")
                if (failed != null) failed
                else if (nullAt == "reconcile") AppResult.Success(null)
                else {
                    val resolved = pending.events.count { it.event.eventId in localEventIds }
                    references.removeAll { it.event.eventId in localEventIds }
                    AppResult.Success(
                        CollaborationHistoryReconciliation(resolved, references.size, null)
                    )
                }
            },
            acquireEvents = { actual, local, ids ->
                assertEquals(runtime, actual)
                assertEquals(CollaborationSpaceIncomingPhase.Pending, local.captured.phase)
                contentRequests += ids.toList()
                contentRooms += local
                val failed = step("events")
                failed
                    ?: AppResult.Success(
                        CollaborationSpaceOriginalIncomingBatch(
                            runtime,
                            local,
                            ids.map {
                                CollaborationIncomingHistoryResult(
                                    it,
                                    CollaborationIncomingHistoryOutcome.Retained(
                                        listOf(it),
                                        emptyList(),
                                    ),
                                )
                            },
                        )
                    )
            },
            completeReferences = { actual, local, pending, batch ->
                assertEquals(runtime, actual)
                assertEquals(physicalDevice, local.traversal.deviceId)
                assertEquals(
                    pending.events.map { it.event.eventId },
                    batch?.items?.map { it.sourceEventId } ?: emptyList(),
                )
                completionSources += pending
                val failed = step("complete-references")
                if (failed != null) failed
                else
                    AppResult.Success(
                        if (nullAt == "complete-references") null
                        else
                            CollaborationSpaceIncomingReferenceCompletion(
                                pending.events.size,
                                if (retryRequired) pending.events.size else 0,
                                moreReferences,
                                retryRequired,
                            )
                    )
            },
            completeRoom = { actual, local ->
                assertEquals(runtime, actual)
                assertEquals(physicalDevice, local.traversal.deviceId)
                val failed = step("complete-room")
                if (failed != null) failed
                else {
                    if (completeResult) current = null
                    AppResult.Success(completeResult)
                }
            },
            recoverProvider = { actual, local ->
                assertEquals(runtime, actual)
                assertEquals(physicalDevice, local.traversal.deviceId)
                val failed = step("recover")
                if (failed != null) failed
                else {
                    if (recoveryResult)
                        current = current?.copy(phase = CollaborationSpaceIncomingPhase.Latest)
                    AppResult.Success(recoveryResult)
                }
            },
        )

    suspend fun run(requested: CollaborationSpaceIncomingRoomDemand = captured) =
        turn(runtime, requested)
}
