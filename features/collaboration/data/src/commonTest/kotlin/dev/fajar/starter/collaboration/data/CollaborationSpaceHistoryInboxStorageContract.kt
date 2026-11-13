package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceInboxRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceHistoryInboxStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun publicationPreservesSourceCiphertextAndIsVisibleAfterReopening() = withDatabase { f ->
        f.merge(f.event())
        val history = f.raw()
        val key = f.keys.read()
        val proposal = f.proposal()
        assertEquals(AppResult.Success(true), f.publish(proposal))
        assertEquals(history, f.raw())
        assertEquals(proposal.entry, f.inbox().entry)
        f.reopen()
        assertEquals(proposal.entry, f.inbox().entry)
        assertEquals(history, f.raw())
        assertEquals(key, f.keys.read())
    }

    @Test
    fun exactReplayDoesNotAdvanceSourceOrIndexAndNeverSubmitsAnEmptyMutation() = withDatabase { f ->
        f.merge(f.event())
        assertEquals(AppResult.Success(true), f.publish(f.proposal()))
        val source = f.raw()
        val index = f.rawInbox()
        val proposal = f.proposal()
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean = error("Replay must not write")
                }
            )
        assertEquals(AppResult.Success(true), f.publish(proposal))
        assertEquals(source, f.raw())
        assertEquals(index, f.rawInbox())
    }

    @Test
    fun changedSourceBeforePublicationLeavesTheDestinationEmpty() = withDatabase { f ->
        f.merge(f.event())
        val revision = f.revision()
        val proposal = f.proposal()
        f.merge(f.event(2))
        assertEquals(AppResult.Success(false), f.publish(proposal, revision))
        assertNull(f.inbox().entry)
        assertNull(f.rawInbox().checkpoint)
    }

    @Test
    fun sourceChangedInsideTheCommitCannotPublishAnOldPreview() = withDatabase { f ->
        f.merge(f.event())
        val originalRepository = f.repository
        val proposal = f.proposal()
        var commits = 0
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        commits++
                        assertEquals(2, mutations.size)
                        assertIs<ReplicaMutation.Check>(mutations.first())
                        assertEquals(
                            AppResult.Success(true),
                            originalRepository.merge(
                                f.access,
                                f.conversation,
                                f.room,
                                listOf(f.event(2)),
                            ),
                        )
                        return f.database.replicas.mutate(scope, mutations, wake)
                    }
                }
            )
        assertEquals(AppResult.Success(false), f.publish(proposal))
        assertEquals(1, commits)
        assertNull(f.inbox().entry)
    }

    @Test
    fun aConcurrentDestinationWriteRetainsItsDraftAndRejectsTheOlderProposal() = withDatabase { f ->
        f.merge(f.event())
        val proposal = f.proposal()
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    f.conversation,
                    historyId(555),
                    "Keep this independent draft",
                    Instant.fromEpochMilliseconds(f.event().timestamp + 1000),
                ),
                CollaborationSpaceInboxPosition(f.event().timestamp + 1000, 1),
            )
        val winner =
            CollaborationSpaceInboxPublication(
                null,
                1,
                collaborationSpaceInboxEntry(f.conversation, f.createdAt(), draft, null),
                draft = draft,
            )
        var commits = 0
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        commits++
                        assertEquals(AppResult.Success(true), f.index().publish(f.access, winner))
                        return f.database.replicas.mutate(scope, mutations, wake)
                    }
                }
            )
        assertEquals(AppResult.Success(false), f.publish(proposal))
        assertEquals(1, commits)
        assertEquals(draft, f.inbox().draft)
        f.repository = f.repository()
        assertEquals(AppResult.Success(true), f.publish(f.proposal()))
        assertEquals(draft, f.inbox().draft)
        assertEquals(draft, f.inbox().entry?.draft)
    }

    @Test
    fun hiddenPreviewAndEditedPreviewKeepTheOriginalAnchorAndIndependentDraft() =
        withDatabase { f ->
            f.merge(f.event())
            assertEquals(AppResult.Success(true), f.publish(f.proposal()))
            val initial = assertNotNull(f.inbox().entry?.newest)
            val edited = f.proposal("A retained edit")
            assertEquals(AppResult.Success(true), f.publish(edited))
            assertEquals(initial.position, f.inbox().entry?.newest?.position)
            assertEquals(AppResult.Success(true), f.publish(f.proposal(null)))
            assertNull(f.inbox().entry?.newest)
            val activities =
                assertIs<AppResult.Success<CollaborationSpaceInboxActivities>>(
                        f.index().activities(f.access, f.conversation, listOf(initial.source))
                    )
                    .value
            assertEquals(listOf(initial.copy(preview = null)), activities.values)
            f.reopen()
            assertEquals(
                AppResult.Success(true),
                f.publish(f.proposal("Restored retained original")),
            )
            assertEquals(initial.position, f.inbox().entry?.newest?.position)
        }

    @Test
    fun wrongRoomOwnerAndMissingSourceCannotWriteOrCreateKeys() = withDatabase { f ->
        val proposal = f.proposal()
        assertEquals(AppResult.Success(false), f.publish(proposal, historyId(9) + ":0"))
        assertNull(f.keys.read())
        f.merge(f.event())
        val revision = f.revision()
        assertIs<AppResult.Failed>(
            f.repository.publishInbox(
                f.access,
                f.conversation,
                "!wrong:example.test",
                revision,
                proposal,
            )
        )
        assertEquals(
            AppResult.Success(false),
            f.repository.publishInbox(
                f.access.copy(spaceId = historyId(9999)),
                f.conversation,
                f.room,
                revision,
                proposal,
            ),
        )
        assertNull(f.inbox().entry)
    }

    @Test
    fun missingRetainedKeyDoesNotRegenerateOverHistoryOrCreateAnIndex() = withDatabase { f ->
        f.merge(f.event())
        val before = f.raw()
        val proposal = f.proposal()
        val revision = f.revision()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.publish(proposal, revision))
        assertNull(f.keys.read())
        assertEquals(before, f.raw())
        assertNull(f.rawInbox().checkpoint)
    }

    @Test
    fun lostCommitReplyIsRecoveredByExactLocalReplay() = withDatabase { f ->
        f.merge(f.event())
        val history = f.raw()
        val proposal = f.proposal()
        var commits = 0
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        commits++
                        assertTrue(f.database.replicas.mutate(scope, mutations, wake))
                        throw IllegalStateException("Lost publication response")
                    }
                }
            )
        assertIs<AppResult.Failed>(f.publish(proposal))
        assertEquals(1, commits)
        assertEquals(proposal.entry, f.inbox().entry)
        val committed = f.rawInbox()
        f.repository = f.repository()
        assertEquals(AppResult.Success(true), f.publish(f.proposal()))
        assertEquals(committed, f.rawInbox())
        assertEquals(history, f.raw())
    }

    @Test
    fun cancellationJoinsTheUncertainCommitWithoutReportingSuccess() = withDatabase { f ->
        f.merge(f.event())
        val proposal = f.proposal()
        val revision = f.revision()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean =
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            f.database.replicas.mutate(scope, mutations, wake)
                        }
                }
            )
        coroutineScope {
            var reported = false
            val job = launch {
                f.publish(proposal, revision)
                reported = true
            }
            entered.await()
            job.cancel()
            assertFalse(job.isCompleted)
            release.complete(Unit)
            job.join()
            assertFalse(reported)
            assertTrue(job.isCancelled)
        }
        assertEquals(proposal.entry, f.inbox().entry)
        f.repository = f.repository()
        assertEquals(AppResult.Success(true), f.publish(f.proposal()))
    }

    @Test
    fun replayRechecksSourceAndIndexWithoutRetryingAChangedSnapshot() = withDatabase { f ->
        f.merge(f.event())
        assertEquals(AppResult.Success(true), f.publish(f.proposal()))
        val proposal = f.proposal()
        val revision = f.revision()
        val originalRepository = f.repository
        var sourceChecks = 0
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        if (partition == spaceHistoryPartition(f.access, f.conversation)) {
                            sourceChecks++
                            if (sourceChecks == 2)
                                assertEquals(
                                    AppResult.Success(true),
                                    originalRepository.merge(
                                        f.access,
                                        f.conversation,
                                        f.room,
                                        listOf(f.event(2)),
                                    ),
                                )
                        }
                        return f.database.replicas.checkpoint(scope, partition)
                    }

                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean = error("No replay write")
                }
            )
        assertEquals(AppResult.Success(false), f.publish(proposal, revision))
        assertEquals(2, sourceChecks)
    }

    @Test
    fun callerActivityMutationAfterEntryCannotReplaceTheCapturedPublication() = withDatabase { f ->
        f.merge(f.event())
        val proposal = f.proposal()
        val submitted = proposal.activities.toMutableList()
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        submitted.clear()
                        return f.database.replicas.checkpoint(scope, partition)
                    }
                }
            )
        assertEquals(AppResult.Success(true), f.publish(proposal.copy(activities = submitted)))
        assertEquals(proposal.entry, f.inbox().entry)
    }

    @Test
    fun sourcePublicationCannotModifyDraftsOrAnotherConversationsEntry() = withDatabase { f ->
        f.merge(f.event())
        val proposal = f.proposal()
        val source = f.raw()
        val other = proposal.copy(entry = proposal.entry.copy(conversationId = historyId(9997)))
        assertIs<AppResult.Failed>(f.publish(other))
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    f.conversation,
                    historyId(555),
                    "Keep separate",
                    Instant.fromEpochMilliseconds(f.event().timestamp + 1),
                ),
                CollaborationSpaceInboxPosition(f.event().timestamp + 1, 2),
            )
        val withDraft =
            proposal.copy(
                lastOrdinal = 2,
                draft = draft,
                entry =
                    collaborationSpaceInboxEntry(
                        f.conversation,
                        f.createdAt(),
                        draft,
                        proposal.entry.newest,
                    ),
            )
        assertTrue(validCollaborationSpaceInboxPublication(withDraft))
        assertIs<AppResult.Failed>(f.publish(withDraft))
        assertNull(f.inbox().entry)
        assertEquals(source, f.raw())
    }

    @Test
    fun replayCannotConfirmAnIndexChangedAfterItsSourceCheck() = withDatabase { f ->
        f.merge(f.event())
        assertEquals(AppResult.Success(true), f.publish(f.proposal()))
        val proposal = f.proposal()
        val winner = f.proposal("A newer retained preview")
        val revision = f.revision()
        var checks = 0
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        if (partition == spaceInboxPartition(f.access)) {
                            checks++
                            if (checks == 2)
                                assertEquals(
                                    AppResult.Success(true),
                                    f.index().publish(f.access, winner),
                                )
                        }
                        return f.database.replicas.checkpoint(scope, partition)
                    }

                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean = error("Replay cannot write")
                }
            )
        assertEquals(AppResult.Success(false), f.publish(proposal, revision))
        assertEquals(2, checks)
        assertEquals(winner.entry, f.inbox().entry)
    }

    @Test
    fun accountReplacementAtTheCommitCannotWriteEitherAccountsIndex() = withDatabase { f ->
        f.merge(f.event())
        val source = f.raw()
        val proposal = f.proposal()
        var commits = 0
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        commits++
                        f.activate(
                            f.access.copy(sessionId = "other-login", accountId = historyId(9994))
                        )
                        return f.database.replicas.mutate(scope, mutations, wake)
                    }
                }
            )
        assertEquals(AppResult.Success(false), f.publish(proposal))
        assertEquals(1, commits)
        f.activate()
        assertNull(f.rawInbox().checkpoint)
        assertEquals(source, f.raw())
    }

    @Test
    fun aStorageFailureStopsAfterOneCommitAttemptWithoutLosingTheSource() = withDatabase { f ->
        f.merge(f.event())
        val source = f.raw()
        val proposal = f.proposal()
        var commits = 0
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        commits++
                        throw IllegalStateException("Fixture storage failure")
                    }
                }
            )
        assertIs<AppResult.Failed>(f.publish(proposal))
        assertEquals(1, commits)
        assertEquals(source, f.raw())
        assertNull(f.rawInbox().checkpoint)
    }

    private fun CollaborationSpaceHistoryFixture.index() =
        EncryptedCollaborationSpaceInboxRepository(database.replicas, cipher, keys)

    private suspend fun CollaborationSpaceHistoryFixture.inbox() =
        assertIs<AppResult.Success<CollaborationSpaceInboxConversation>>(
                index().conversation(access, conversation)
            )
            .value

    private suspend fun CollaborationSpaceHistoryFixture.rawInbox() =
        database.replicas
            .observe(spaceHistoryScope(access), spaceInboxPartition(access), limit = 100)
            .first()

    private suspend fun CollaborationSpaceHistoryFixture.revision() =
        assertNotNull(raw().checkpoint).let { it.handle.generation + ":" + it.revision }

    private fun CollaborationSpaceHistoryFixture.createdAt() = event().timestamp - 1000

    private suspend fun CollaborationSpaceHistoryFixture.publish(
        publication: CollaborationSpaceInboxPublication,
        sourceRevision: String? = null,
    ): AppResult<Boolean> {
        val revision = sourceRevision ?: revision()
        return repository.publishInbox(access, conversation, room, revision, publication)
    }

    private suspend fun CollaborationSpaceHistoryFixture.proposal(
        text: String? = event().text
    ): CollaborationSpaceInboxPublication {
        val current = inbox()
        val event = event()
        val source =
            CollaborationSpaceInboxSource.History(
                null,
                conversation,
                room,
                event.sender,
                event.eventId,
            )
        val existing =
            assertIs<AppResult.Success<CollaborationSpaceInboxActivities>>(
                    index().activities(access, conversation, listOf(source))
                )
                .value
                .values
                .singleOrNull()
        val ordinal = current.version?.lastOrdinal ?: 0L
        val position =
            existing?.position ?: CollaborationSpaceInboxPosition(event.timestamp, ordinal + 1)
        val activity =
            CollaborationSpaceInboxActivity(
                source,
                position,
                text?.let {
                    CollaborationSpaceInboxPreview(
                        CollaborationSpaceInboxContent.TEXT,
                        it,
                        event.timestamp,
                        null,
                    )
                },
            )
        return CollaborationSpaceInboxPublication(
            current.version,
            ordinal + if (existing == null) 1 else 0,
            collaborationSpaceInboxEntry(
                conversation,
                createdAt(),
                current.draft,
                activity.takeIf { it.preview != null },
            ),
            activities = listOf(activity),
        )
    }

    private fun withDatabase(block: suspend (CollaborationSpaceHistoryFixture) -> Unit) = runTest {
        val fixture = CollaborationSpaceHistoryFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
