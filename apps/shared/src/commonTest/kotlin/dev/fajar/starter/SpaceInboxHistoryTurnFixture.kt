package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationSpaceInboxHistoryTurnWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class SpaceInboxHistoryTurnFixture {
    val access = CollaborationSpaceLocalAccess(id(1), "https://example.test", id(2), id(3))
    val conversation = id(4)
    val otherConversation = id(5)
    val passId = id(40)
    val trigger = CollaborationSpaceInboxHistoryTrigger(null, null, null, null)
    val failure = Failure(FailureKind.Storage, "Fixture storage unavailable")
    val denied = Failure(FailureKind.AccessDenied, "Fixture access denied")
    val event = "${'$'}event:example.test"
    val calls = mutableListOf<String>()
    val failures = mutableMapOf<String, Failure>()
    val lostReplies = mutableMapOf<String, Failure>()
    var before: suspend (String) -> Unit = {}
    var after: suspend (String) -> Unit = {}
    var destination: CollaborationSpaceInboxVersion? = null
    var global: CollaborationSpaceInboxHistoryCheckpoint? = null
    val sources = linkedMapOf<String, CollaborationSpaceInboxHistorySourceCheckpoint>()
    val changes = mutableListOf<CollaborationSpaceInboxHistoryChange>()
    var pendingReferences = emptyList<CollaborationSpaceInboxHistoryRepairReference>()
    var historyResponse:
        (CollaborationSpaceInboxHistorySourceCheckpoint) -> CollaborationSpaceHistoryChangesRead =
        { current ->
            val cursor = current.progress.historyCursor
            val lower =
                when (cursor) {
                    is CollaborationHistoryChangeCursor.After -> cursor.revision
                    is CollaborationHistoryChangeCursor.Within -> cursor.window.afterRevision
                    null -> null
                }
            val window = CollaborationHistoryChangeWindow(current.progress.owner, id(60), lower, 4)
            CollaborationSpaceHistoryChangesRead(
                current.progress.owner,
                CollaborationHistoryChanges.Page(
                    window,
                    "${id(60)}:4",
                    if (lower == 4L) emptyList() else listOf(event),
                    null,
                ),
            )
        }

    fun id(value: Int) = "e7000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    fun source(conversationId: String = conversation, pass: String = passId) =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(id(50), 5),
            CollaborationSpaceInboxHistorySourceProgress(
                conversationId,
                CollaborationHistoryChangeOwner(
                    access.audience,
                    access.accountId,
                    CollaborationHistoryChangeOwnerKind.SPACE,
                    access.spaceId,
                    conversationId,
                    "!room:example.test",
                ),
                null,
                pass,
                null,
                false,
                null,
            ),
        )

    fun queued(ids: List<String> = listOf(conversation), catalog: Boolean = false) {
        val start = success(observeCollaborationSpaceInboxHistory(null, trigger, passId))
        val initial =
            if (catalog) start
            else
                start.copy(
                    pass =
                        start.pass!!.copy(
                            traversal =
                                CollaborationSpaceInboxHistoryTraversal.Sources(
                                    null,
                                    null,
                                    false,
                                    false,
                                )
                        )
                )
        global =
            CollaborationSpaceInboxHistoryCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(id(41), 0),
                success(captureCollaborationSpaceInboxHistoryBatch(initial, ids, null)),
            )
    }

    fun ready() {
        queued()
        val retained = assertNotNull(global)
        global =
            retained.copy(
                progress =
                    retained.progress.copy(
                        pass =
                            retained.progress.pass!!.copy(
                                traversal = CollaborationSpaceInboxHistoryTraversal.Ready
                            )
                    )
            )
    }

    suspend fun turn(recover: Boolean = false) = worker()(access, trigger, null, id(99), recover)

    fun worker() =
        CollaborationSpaceInboxHistoryTurnWorker(
            readProgress = { owner -> checked(owner, "global-read") { AppResult.Success(global) } },
            changeProgress = { owner, expected, change ->
                fakeGlobalChange(owner, expected, change)
            },
            readCatalog = { owner, _, limit ->
                checked(owner, "catalog") {
                    assertEquals(16, limit)
                    AppResult.Success(
                        CollaborationSpaceConversationCatalogRead.Page(
                            null,
                            emptyList(),
                            null,
                            false,
                        )
                    )
                }
            },
            readSources = { owner, _, limit ->
                checked(owner, "sources") {
                    assertEquals(16, limit)
                    AppResult.Success(
                        CollaborationSpaceInboxHistorySourcePage(
                            sources.values.sortedBy { it.progress.conversationId },
                            null,
                        )
                    )
                }
            },
            readSource = { owner, id ->
                checked(owner, "source-read") { AppResult.Success(sources[id]) }
            },
            prepareSource = { owner, id, pass -> fakePreparation(owner, id, pass) },
            readHistory = { owner, id, cursor, limit ->
                checked(owner, "history") {
                    assertEquals(16, limit)
                    val current = assertNotNull(sources[id])
                    assertEquals(current.progress.historyCursor, cursor)
                    AppResult.Success(historyResponse(current))
                }
            },
            confirmHistory = { owner, id, _, read -> fakeConfirmation(owner, id, read) },
            publishHistory = { owner, id, ids -> fakePublication(owner, id, ids) },
            retainHistory = { owner, expected, read, receipt ->
                checked(owner, "history-retain") {
                    val proposed =
                        collaborationSpaceInboxHistoryHandoff(access, expected, read, receipt)
                    if (proposed is AppResult.Failed) proposed
                    else updateSource(expected, (proposed as AppResult.Success).value.replacement)
                }
            },
            restartSource = { owner, expected, _ ->
                checked(owner, "restart") {
                    updateSource(
                        expected,
                        expected.progress.copy(
                            historyCursor = null,
                            historyComplete = false,
                            pendingWindow = null,
                        ),
                    )
                }
            },
            readPending = { owner, expected, limit -> fakePendingPage(owner, expected, limit) },
            completePending = { owner, expected, page ->
                checked(owner, "pending-complete") {
                    val proposed =
                        collaborationSpaceInboxHistoryPendingCompletion(
                            access,
                            expected,
                            page,
                            destination,
                        )
                    if (proposed is AppResult.Failed) proposed
                    else updateSource(expected, (proposed as AppResult.Success).value.replacement)
                }
            },
            retainPending = { owner, expected, page, receipt ->
                checked(owner, "pending-retain") {
                    val proposed =
                        collaborationSpaceInboxHistoryPendingHandoff(
                            access,
                            expected,
                            page,
                            receipt,
                        )
                    if (proposed is AppResult.Failed) proposed
                    else updateSource(expected, (proposed as AppResult.Success).value.replacement)
                }
            },
        )

    private suspend fun fakeGlobalChange(
        owner: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceInboxHistoryCheckpoint?,
        change: CollaborationSpaceInboxHistoryChange,
    ) =
        checked(owner, "global-write") {
            changes += change
            if (global != expected) conflict()
            else {
                val proposed = collaborationSpaceInboxHistoryMutation(access, expected, change)
                if (proposed is AppResult.Failed) proposed
                else {
                    val mutation = (proposed as AppResult.Success).value
                    val allowed =
                        when (val guard = mutation.guard) {
                            is CollaborationSpaceInboxHistoryGuard.Source ->
                                sources[guard.checkpoint.progress.conversationId] ==
                                    guard.checkpoint
                            is CollaborationSpaceInboxHistoryGuard.Destination ->
                                collaborationSpaceInboxHistoryDestinationCovers(
                                    guard.version,
                                    destination,
                                )
                        }
                    if (!allowed) conflict()
                    else {
                        val retained =
                            CollaborationSpaceInboxHistoryCheckpoint(
                                CollaborationSpaceInboxHistoryProgressVersion(
                                    expected?.version?.generation ?: id(41),
                                    expected?.version?.revision?.plus(1) ?: 0L,
                                ),
                                mutation.replacement,
                            )
                        global = retained
                        AppResult.Success(retained)
                    }
                }
            }
        }

    private suspend fun fakePreparation(
        owner: CollaborationSpaceLocalAccess,
        id: String,
        pass: String,
    ) =
        checked(owner, "prepare") {
            val current = sources[id]
            val proposed =
                prepareCollaborationSpaceInboxHistorySource(
                    access,
                    id,
                    source(id).progress.owner,
                    pass,
                    destination,
                    current,
                )
            if (proposed is AppResult.Failed) proposed
            else AppResult.Success(saveSource(current, (proposed as AppResult.Success).value))
        }

    private suspend fun fakeConfirmation(
        owner: CollaborationSpaceLocalAccess,
        id: String,
        read: CollaborationSpaceHistoryChangesRead,
    ) =
        checked(owner, "confirm") {
            val page = read.changes as? CollaborationHistoryChanges.Page
            AppResult.Success(
                CollaborationSpaceInboxHistoryCommit(
                    assertNotNull(sources[id]).progress.owner,
                    CollaborationHistoryInboxReceipt(
                        page?.let {
                            CollaborationHistorySourceVersion(
                                it.window.generation,
                                it.window.throughRevision,
                            )
                        },
                        destination,
                    ),
                    emptyList(),
                    emptyList(),
                )
            )
        }

    private suspend fun fakePublication(
        owner: CollaborationSpaceLocalAccess,
        id: String,
        ids: List<String>,
    ) =
        checked(owner, "publish") {
            AppResult.Success(
                CollaborationSpaceInboxHistoryCommit(
                    assertNotNull(sources[id]).progress.owner,
                    CollaborationHistoryInboxReceipt(
                        CollaborationHistorySourceVersion(this.id(60), 4),
                        destination,
                    ),
                    ids.toList(),
                    emptyList(),
                )
            )
        }

    private suspend fun fakePendingPage(
        owner: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceInboxHistorySourceCheckpoint,
        limit: Int,
    ) =
        checked(owner, "pending") {
            assertEquals(16, limit)
            if (sources[expected.progress.conversationId] != expected) conflict()
            else
                AppResult.Success(
                    CollaborationSpaceInboxHistoryPendingPage(
                        expected.version,
                        expected.progress.pendingWindow
                            ?: CollaborationSpaceInboxHistoryPendingWindow(
                                expected.version.generation,
                                expected.version.revision,
                                null,
                                false,
                            ),
                        pendingReferences,
                        null,
                    )
                )
        }

    private suspend fun <T> checked(
        owner: CollaborationSpaceLocalAccess,
        name: String,
        action: () -> AppResult<T>,
    ): AppResult<T> {
        assertEquals(access, owner)
        calls += name
        before(name)
        failures[name]?.let {
            return AppResult.Failed(it)
        }
        val result = action()
        after(name)
        return lostReplies[name]?.let { AppResult.Failed(it) } ?: result
    }

    private fun updateSource(
        expected: CollaborationSpaceInboxHistorySourceCheckpoint,
        replacement: CollaborationSpaceInboxHistorySourceProgress,
    ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint> =
        if (sources[expected.progress.conversationId] != expected) conflict()
        else AppResult.Success(saveSource(expected, replacement))

    private fun saveSource(
        expected: CollaborationSpaceInboxHistorySourceCheckpoint?,
        replacement: CollaborationSpaceInboxHistorySourceProgress,
    ): CollaborationSpaceInboxHistorySourceCheckpoint {
        if (expected != null && expected.progress == replacement) return expected
        return CollaborationSpaceInboxHistorySourceCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(
                    expected?.version?.generation ?: id(50),
                    expected?.version?.revision?.plus(1) ?: 0L,
                ),
                replacement,
            )
            .also { sources[replacement.conversationId] = it }
    }

    private fun conflict() =
        collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)

    private fun <T> success(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value
}
