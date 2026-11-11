package dev.fajar.starter.fixtures

import dev.fajar.starter.app.work.CollaborationSpaceIncomingWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Task-lifetime outcomes; domain and real encrypted storage have their own behavioral contracts.
 */
internal class SpaceIncomingWorkerFixture(
    scheduler: TestCoroutineScheduler,
    count: Int = 1,
    platform: AppPlatform = AppPlatform.Android,
) {
    val base = RecoveryWorkerFixture(scheduler, count = 0)
    val owner = CollaborationSpaceOwner(base.parent, recoveryId(800))
    val access = owner.localDirectoryAccess()
    val runtime = CollaborationSpaceRuntimeReady(owner, recoveryId(801), recoveryId(802))
    var original: CollaborationSpaceRecoveryDevice? =
        CollaborationSpaceRecoveryDevice(owner, recoveryId(803), runtime.operationId)
    var wake: CollaborationSpaceIncomingWake? = CollaborationSpaceIncomingWake(recoveryId(804))
    var present = true
    var scanning = false
    var providerMore = false
    var referencesMore = false
    var hiddenHead = false
    var missingKeys = false
    var missingProgress = false
    var referencesFailure: Failure? = null
    var pageFailure: Failure? = null
    var resumeFailure: Failure? = null
    var finishResult = true
    var before: suspend (String) -> Unit = {}
    val calls = mutableListOf<String>()
    val attempted = mutableListOf<String>()
    val deferred = mutableListOf<Pair<String, Failure?>>()
    val source = CollaborationSpaceIncomingSource("1".repeat(64), "@fixture:example.test", "DEVICE")
    var installed: CollaborationSpaceIncomingSource? = source
    val sources = mutableListOf(CollaborationSpaceIncomingSourceProgress(source, "position"))
    val physicalRooms = mutableListOf<CollaborationSpaceIncomingPhysicalRoomDemand>()
    val bindingTargets = mutableMapOf<String, String>()
    val physicalAttempts = mutableListOf<String>()
    val deferredPhysical = mutableListOf<CollaborationSpaceIncomingPhysicalRoomDemand>()
    var bindingFailure: Failure? = null
    var nextPosition = count.toLong()
    val rooms =
        (0 until count)
            .map { index ->
                CollaborationSpaceIncomingRoomDemand(
                    recoveryId(1000 + index),
                    recoveryId(2000 + index),
                    index.toLong(),
                    "\$head-$index",
                    CollaborationSpaceIncomingPhase.Latest,
                )
            }
            .toMutableList()

    suspend fun step(name: String) {
        calls += name
        before(name)
    }

    fun work(
        selectedSource: CollaborationSpaceIncomingSource? = null
    ): CollaborationSpaceIncomingWork {
        val demand =
            CollaborationSpaceIncomingDemand(
                nextPosition,
                rooms.toList(),
                if (scanning) recoveryId(805) else null,
                sources = sources.toList(),
                physicalRooms = physicalRooms.toList(),
            )
        return (selectCollaborationSpaceIncomingWork(
                if (present) CollaborationSpaceIncomingDemandSnapshot(recoveryId(806), demand)
                else null,
                base.clock.now().toEpochMilliseconds(),
                selectedSource,
            )
                as AppResult.Success)
            .value
    }

    fun queuePhysical(
        index: Int,
        conversationId: String? = null,
        from: CollaborationSpaceIncomingSource = source,
    ): CollaborationSpaceIncomingPhysicalRoomDemand {
        if (sources.none { it.source == from })
            sources += CollaborationSpaceIncomingSourceProgress(from, "other")
        val physical =
            CollaborationSpaceIncomingPhysicalRoomDemand(
                from.storeId,
                "!physical$index:example.test",
                recoveryId(3000 + index),
                nextPosition++,
                "\$physical-$index",
                CollaborationSpaceIncomingMembership.Joined,
                false,
            )
        physicalRooms += physical
        if (conversationId != null) bindingTargets[physical.roomId] = conversationId
        return physical
    }

    val worker =
        CollaborationSpaceIncomingWorker(
            states = lazy { base.states },
            ensureWake = {
                step("ensure")
                if (wake == null) wake = CollaborationSpaceIncomingWake(recoveryId(804))
                AppResult.Success(wake!!)
            },
            readWake = {
                step("wake")
                AppResult.Success(wake)
            },
            requestScan = { actual, _ ->
                check(actual == access)
                step("scan-request")
                present = true
                scanning = true
                AppResult.Success(true)
            },
            readDemand = { actual ->
                check(actual == access)
                step("demand")
                AppResult.Success(work())
            },
            readRuntimeDemand = { actual ->
                check(actual == runtime)
                step("runtime-demand")
                val selected = installed
                if (selected == null)
                    AppResult.Failed(Failure(FailureKind.Unavailable, "Fixture source missing"))
                else AppResult.Success(work(selected))
            },
            resolveBinding = { actual, captured ->
                check(actual == runtime)
                physicalAttempts += captured.roomId
                step("binding")
                val target = bindingTargets[captured.roomId]
                val failure = bindingFailure
                if (failure != null) AppResult.Failed(failure)
                else if (target == null)
                    AppResult.Success(CollaborationSpaceIncomingBindingResolution.BindingMissing)
                else {
                    val planned =
                        resolveCollaborationSpaceIncomingPhysicalRoom(
                            work().snapshot!!.demand,
                            captured,
                            target,
                        )
                    if (planned is AppResult.Success) {
                        rooms.clear()
                        rooms.addAll(planned.value.demand.rooms)
                        physicalRooms.clear()
                        physicalRooms.addAll(planned.value.demand.physicalRooms)
                        AppResult.Success(planned.value.outcome)
                    } else planned as AppResult.Failed
                }
            },
            deferPhysical = { actual, captured, failure ->
                check(actual == runtime)
                step("defer-physical")
                if (isCollaborationSpaceIncomingQueueContention(failure))
                    AppResult.Failed(requireNotNull(failure))
                else {
                    val planned =
                        deferCollaborationSpaceIncomingPhysicalRoom(
                            work().snapshot!!.demand,
                            captured,
                            base.clock.now().toEpochMilliseconds(),
                        )
                    if (planned is AppResult.Success) {
                        deferredPhysical += captured
                        physicalRooms.clear()
                        physicalRooms.addAll(planned.value.physicalRooms)
                        nextPosition = planned.value.nextPosition
                        AppResult.Success(true)
                    } else planned as AppResult.Failed
                }
            },
            discover = { actual ->
                check(actual == access)
                step("discover")
                scanning = false
                AppResult.Success(true)
            },
            finish = { actual, captured ->
                check(actual == access)
                step("finish")
                val done = finishResult && !work().roomsPending && !scanning && wake == captured
                if (done) wake = null
                AppResult.Success(done)
            },
            resume = {
                step("resume")
                resumeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(wake != null)
            },
            device = { parent ->
                check(parent == base.parent)
                step("device")
                AppResult.Success(original)
            },
            adopt = {
                step("adopt")
                AppResult.Success(runtime)
            },
            room = { actual, captured ->
                check(actual == runtime)
                step("room")
                attempted += captured.conversationId
                val room = rooms.singleOrNull { it == captured }
                val conversation =
                    CollaborationSpaceConversation(
                        owner,
                        captured.conversationId,
                        owner.parent.accountId,
                        recoveryId(810),
                        owner.parent.accountId,
                        base.clock.now(),
                        1,
                    )
                AppResult.Success(
                    room?.let {
                        CollaborationSpaceIncomingRoom(it, conversation, key(it.conversationId))
                    }
                )
            },
            acquirePage = { actual, conversation, origin ->
                check(actual == runtime)
                step("provider")
                pageFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(
                        CollaborationSpaceProviderHistoryAcquisition(
                            runtime,
                            key(conversation.id),
                            origin,
                            null,
                            progress(),
                            1,
                        )
                    )
            },
            advance = { actual, captured, acquired ->
                check(actual == access)
                step("advance")
                missingProgress = false
                val index = rooms.indexOf(captured)
                if (index >= 0)
                    rooms[index] =
                        captured.copy(
                            phase =
                                if (acquired.exhausted) CollaborationSpaceIncomingPhase.Pending
                                else CollaborationSpaceIncomingPhase.Provider,
                            retry = null,
                        )
                AppResult.Success(index >= 0)
            },
            readReferences = { actual, _ ->
                check(actual == access)
                step("references")
                referencesFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(
                        if (missingProgress) CollaborationHistoryReferences(null, emptyList(), null)
                        else
                            CollaborationHistoryReferences(
                                progress(),
                                listOf(
                                    CollaborationHistoryReference(
                                        0,
                                        CollaborationProviderHistoryEvent(
                                            "\$source",
                                            "@fixture:example.test",
                                            1,
                                            "m.room.encrypted",
                                        ),
                                    )
                                ),
                                if (referencesMore) "next" else null,
                            )
                    )
            },
            acquireEvents = { actual, conversation, ids ->
                check(actual == runtime)
                step("content")
                AppResult.Success(
                    CollaborationSpaceIncomingBatch(
                        runtime,
                        conversation.id,
                        key(conversation.id).roomId,
                        ids.map {
                            CollaborationIncomingHistoryResult(
                                it,
                                CollaborationIncomingHistoryOutcome.Unsupported,
                            )
                        },
                        provider(),
                    )
                )
            },
            completeReferences = { actual, _, source, _ ->
                check(actual == access)
                step("retain")
                if (source.progress == null)
                    AppResult.Failed(Failure(FailureKind.Conflict, "Fixture: provider missing"))
                else
                    AppResult.Success(
                        CollaborationSpaceIncomingReferenceCompletion(
                            if (missingKeys) 0 else source.events.size,
                            if (missingKeys) source.events.size else 0,
                            referencesMore,
                            missingKeys,
                        )
                    )
            },
            completeRoom = { actual, _, captured ->
                check(actual == access)
                step("complete")
                val done = !missingKeys && !referencesMore && !hiddenHead
                if (done) rooms.remove(captured)
                AppResult.Success(done)
            },
            recoverProvider = { actual, _, captured ->
                check(actual == access)
                step("recover")
                val required = hiddenHead || missingProgress
                if (required) {
                    val next =
                        nextCollaborationSpaceIncomingDemand(
                            work().snapshot!!.demand,
                            CollaborationSpaceIncomingDemandChange.RestartProvider(
                                captured,
                                if (missingProgress) null
                                else
                                    nextCollaborationSpaceIncomingRetry(
                                        captured.retry,
                                        base.clock.now().toEpochMilliseconds(),
                                    )!!,
                            ),
                        )
                            as AppResult.Success
                    rooms.clear()
                    rooms.addAll(next.value.rooms)
                    nextPosition = next.value.nextPosition
                }
                AppResult.Success(required)
            },
            defer = { actual, captured, failure ->
                check(actual == access)
                step("defer")
                deferred += captured.conversationId to failure
                val next =
                    nextCollaborationSpaceIncomingDemand(
                        work().snapshot!!.demand,
                        CollaborationSpaceIncomingDemandChange.Defer(
                            captured,
                            if (failure == null) null
                            else
                                nextCollaborationSpaceIncomingRetry(
                                    captured.retry,
                                    base.clock.now().toEpochMilliseconds(),
                                ),
                        ),
                    )
                        as AppResult.Success
                rooms.clear()
                rooms.addAll(next.value.rooms)
                nextPosition = next.value.nextPosition
                AppResult.Success(true)
            },
            build = base.build.copy(platform = platform),
        )

    private fun provider() = CollaborationHistoryProviderIdentity(recoveryId(820), recoveryId(821))

    private fun key(id: String) =
        CollaborationHistoryTraversal(
            id,
            "!room:example.test",
            runtime.deviceId,
            CollaborationHistoryPurpose.Incoming,
        )

    private fun progress() =
        CollaborationHistoryProgress(
            recoveryId(830),
            0,
            if (providerMore) "older" else null,
            1,
            CollaborationIncomingHistoryPosition("head", null),
            provider(),
        )
}
