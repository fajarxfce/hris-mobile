package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationSpaceInboxRepairWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

internal class SpaceInboxRepairWorkerFixture {
    val access = CollaborationSpaceLocalAccess(id(1), "https://example.test", id(2), id(3))
    val queued =
        CollaborationSpaceMessageRecord(
            CollaborationSpaceMessageIntent(
                id(4),
                access.messageOwner(),
                id(5),
                Instant.fromEpochMilliseconds(100),
                "Original",
            ),
            0,
            CollaborationMessageState.Queued,
        )
    val reference = CollaborationSpaceInboxRepairReference(queued.intent, 1)
    val sent =
        queued.copy(
            revision = 3,
            state =
                CollaborationMessageState.Sent(
                    CollaborationMessageDevice(id(6), id(7), id(8), "!room:example.test"),
                    "transaction",
                    "${'$'}event",
                    CollaborationMessageOrigin("@sender:example.test", "a".repeat(64)),
                ),
        )
    val storageFailure = Failure(FailureKind.Storage, "Unavailable")
    val records =
        MutableStateFlow<AppResult<List<CollaborationSpaceMessageRecord>>>(
            AppResult.Success(emptyList())
        )
    val demand = MutableStateFlow<AppResult<String?>>(AppResult.Success(null))
    var progress: CollaborationSpaceInboxRepairProgress? = null
    var generation = 100
    val references = linkedMapOf(reference.intent.id to reference)
    val changes = mutableListOf<CollaborationSpaceInboxRepairProgressChange>()
    val pages = mutableListOf<String?>()
    val lookups = mutableListOf<Pair<String, String>>()
    val reconciled = mutableListOf<String>()
    val seeded = mutableListOf<CollaborationSpaceMessageIntent>()
    var onSeed: suspend (CollaborationSpaceMessageIntent) -> AppResult<Boolean> = {
        AppResult.Success(true)
    }
    var commandStarts = 0
    var commandStops = 0
    var requestStarts = 0
    var requestStops = 0
    var progressReads = 0
    var afterChange: suspend (CollaborationSpaceInboxRepairProgressChange) -> Unit = {}
    var onReconcile: suspend (CollaborationSpaceInboxRepairReference) -> AppResult<Boolean> = {
        AppResult.Success(false)
    }
    var onPage: suspend (String?, Int) -> AppResult<CollaborationSpaceInboxRepairPage> =
        { after, limit ->
            val offset = after?.toInt() ?: 0
            val all = references.values.toList()
            val values = all.drop(offset).take(limit)
            val next = (offset + values.size).takeIf { it < all.size }?.toString()
            AppResult.Success(
                CollaborationSpaceInboxRepairPage.Items(
                    CollaborationSpaceInboxVersion(id(9), 1, 1),
                    values,
                    next,
                )
            )
        }

    fun id(value: Int) = "e1000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    fun request(): String {
        val requestedId = id(generation++)
        val next =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress>>(
                    collaborationSpaceInboxRepairProgress(
                        progress,
                        CollaborationSpaceInboxRepairProgressChange.Request(requestedId),
                        id(generation++),
                    )
                )
                .value
        progress = next
        demand.value = AppResult.Success(next.latestDemand)
        return requestedId
    }

    fun worker() =
        CollaborationSpaceInboxRepairWorker(
            messages = { owner ->
                assertEquals(access, owner)
                flow {
                    commandStarts++
                    try {
                        emitAll(records)
                    } finally {
                        commandStops++
                    }
                }
            },
            requests = { owner ->
                assertEquals(access, owner)
                flow {
                    requestStarts++
                    try {
                        emitAll(demand)
                    } finally {
                        requestStops++
                    }
                }
            },
            readProgress = { owner ->
                assertEquals(access, owner)
                progressReads++
                AppResult.Success(progress)
            },
            changeProgress = { owner, change ->
                assertEquals(access, owner)
                changes += change
                val next = collaborationSpaceInboxRepairProgress(progress, change, id(generation++))
                if (next is AppResult.Success) {
                    progress = next.value
                    demand.value = AppResult.Success(next.value.latestDemand)
                    afterChange(change)
                }
                next
            },
            readPage = { owner, after, limit ->
                assertEquals(access, owner)
                assertEquals(16, limit)
                pages += after
                onPage(after, limit).also { result ->
                    if (result is AppResult.Success)
                        assertTrue(
                            validCollaborationSpaceInboxRepairPage(
                                access,
                                after,
                                limit,
                                result.value,
                            )
                        )
                }
            },
            readReference = { owner, conversation, message ->
                assertEquals(access, owner)
                lookups += conversation to message
                AppResult.Success(
                    references[message]?.takeIf { it.intent.conversationId == conversation }
                )
            },
            seed = { owner, original ->
                assertEquals(access, owner)
                seeded += original
                onSeed(original)
            },
            reconcile = { owner, original ->
                assertEquals(access, owner)
                reconciled += original.intent.id
                onReconcile(original)
            },
        )
}
