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
        val signal =
            assertNotNull(
                f.database.replicas.checkpoint(
                    spaceHistoryScope(f.access),
                    CollaborationHistorySignal.PARTITION,
                )
            )
        val key = f.keys.read()
        val proposal = f.proposal()
        assertEquals(AppResult.Success(true), f.publish(proposal))
        assertEquals(history, f.raw())
        assertEquals(
            signal,
            f.database.replicas.checkpoint(
                spaceHistoryScope(f.access),
                CollaborationHistorySignal.PARTITION,
            ),
        )
        assertEquals(proposal.entry, f.inbox().entry)
        f.reopen()
        assertEquals(proposal.entry, f.inbox().entry)
        assertEquals(history, f.raw())
        assertEquals(
            signal,
            f.database.replicas.checkpoint(
                spaceHistoryScope(f.access),
                CollaborationHistorySignal.PARTITION,
            ),
        )
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
                CollaborationHistoryInboxProposal(f.conversation, proposal.expected, proposal),
            )
        )
        assertEquals(
            AppResult.Success(null),
            f.repository.publishInbox(
                f.access.copy(spaceId = historyId(9999)),
                f.conversation,
                f.room,
                revision,
                CollaborationHistoryInboxProposal(f.conversation, proposal.expected, proposal),
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

    @Test
    fun alternatingAbsentPartitionsCannotConfirmAnEmptyPair() = withDatabase { f ->
        val database = f.database
        val scope = spaceHistoryScope(f.access)
        val sourcePartition = spaceHistoryPartition(f.access, f.conversation)
        val indexPartition = spaceInboxPartition(f.access)
        assertTrue(
            database.replicas.replace(
                scope,
                indexPartition,
                null,
                ReplicaSnapshot(historyId(15401), "raw-index-presence", emptyList()),
            )
        )
        var sourceReads = 0
        var swaps = 0
        val store =
            object : ReplicaStore by database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    val value = database.replicas.checkpoint(scope, partition)
                    if (partition == sourcePartition && sourceReads++ == 0) {
                        assertNull(value)
                        val index =
                            assertNotNull(database.replicas.checkpoint(scope, indexPartition))
                        assertTrue(
                            database.replicas.mutate(
                                scope,
                                listOf(
                                    ReplicaMutation.Replace(
                                        sourcePartition,
                                        null,
                                        ReplicaSnapshot(
                                            historyId(15402),
                                            "raw-source-presence",
                                            emptyList(),
                                        ),
                                    ),
                                    ReplicaMutation.Replace(indexPartition, index, null),
                                ),
                            )
                        )
                        swaps++
                    } else if (partition == indexPartition && value == null) {
                        val source =
                            assertNotNull(database.replicas.checkpoint(scope, sourcePartition))
                        assertTrue(
                            database.replicas.mutate(
                                scope,
                                listOf(
                                    ReplicaMutation.Replace(sourcePartition, source, null),
                                    ReplicaMutation.Replace(
                                        indexPartition,
                                        null,
                                        ReplicaSnapshot(
                                            historyId(15403),
                                            "raw-index-presence",
                                            emptyList(),
                                        ),
                                    ),
                                ),
                            )
                        )
                        swaps++
                    }
                    return value
                }
            }
        // Every owned transaction leaves one partition present; there is never an empty pair.
        assertEquals(
            AppResult.Success(null),
            f.repository(store)
                .publishInbox(
                    f.access,
                    f.conversation,
                    f.room,
                    null,
                    CollaborationHistoryInboxProposal(f.conversation, null, null),
                ),
        )
        assertTrue(swaps >= 1)
        assertNull(f.keys.read())
        assertTrue(
            database.replicas.checkpoint(scope, sourcePartition) != null ||
                database.replicas.checkpoint(scope, indexPartition) != null
        )
    }

    @Test
    fun anAbsentPairReturnsAnAbsentReceiptWithoutCreatingKeys() = withDatabase { f ->
        val value =
            f.repository.publishInbox(
                f.access,
                f.conversation,
                f.room,
                null,
                CollaborationHistoryInboxProposal(f.conversation, null, null),
            )
        assertEquals(AppResult.Success(CollaborationHistoryInboxReceipt(null, null)), value)
        assertNull(f.keys.read())
        assertNull(f.raw().checkpoint)
        assertNull(f.rawInbox().checkpoint)
    }

    @Test
    fun sourceOnlyConfirmationReturnsItsVersionWithoutCreatingAnIndex() = withDatabase { f ->
        f.merge(f.event())
        val before = f.raw()
        val source = assertNotNull(before.checkpoint)
        val value =
            assertIs<AppResult.Success<CollaborationHistoryInboxReceipt?>>(
                    f.repository.publishInbox(
                        f.access,
                        f.conversation,
                        f.room,
                        f.revision(),
                        CollaborationHistoryInboxProposal(f.conversation, null, null),
                    )
                )
                .value
        assertEquals(
            CollaborationHistoryInboxReceipt(
                CollaborationHistorySourceVersion(source.handle.generation, source.revision),
                null,
            ),
            value,
        )
        assertEquals(before, f.raw())
        assertNull(f.rawInbox().checkpoint)
        f.reopen()
        assertEquals(before, f.raw())
    }

    @Test
    fun committedReceiptUsesTheWrittenGenerationEvenIfTheIndexIsRemovedBeforeTheReply() =
        withDatabase { f ->
            f.merge(f.event())
            val source = f.raw()
            val proposal = f.proposal()
            var written: CollaborationSpaceInboxVersion? = null
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        val mutation = assertIs<ReplicaMutation.Replace>(mutations.last())
                        written =
                            CollaborationSpaceInboxVersion(
                                assertNotNull(mutation.snapshot).generation,
                                0,
                                proposal.lastOrdinal,
                            )
                        assertTrue(f.database.replicas.mutate(scope, mutations, wake))
                        val partition = spaceInboxPartition(f.access)
                        val current =
                            assertNotNull(f.database.replicas.checkpoint(scope, partition))
                        assertTrue(f.database.replicas.replace(scope, partition, current, null))
                        return true
                    }
                }
            val receipt =
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationHistoryInboxReceipt?>>(
                            f.repository(store)
                                .publishInbox(
                                    f.access,
                                    f.conversation,
                                    f.room,
                                    f.revision(),
                                    CollaborationHistoryInboxProposal(
                                        f.conversation,
                                        proposal.expected,
                                        proposal,
                                    ),
                                )
                        )
                        .value
                )
            assertEquals(assertNotNull(written), receipt.destination)
            assertNull(f.rawInbox().checkpoint)
            assertEquals(source, f.raw())
        }

    @Test
    fun checkedNoOpPreservesTheIndexAndSourceSignalAndRejectsAStaleVersion() = withDatabase { f ->
        f.merge(f.event())
        assertEquals(AppResult.Success(true), f.publish(f.proposal()))
        val version = assertNotNull(f.inbox().version)
        val index = f.rawInbox()
        val source = f.raw()
        val signal =
            f.database.replicas.checkpoint(
                spaceHistoryScope(f.access),
                CollaborationHistorySignal.PARTITION,
            )
        val receipt =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationHistoryInboxReceipt?>>(
                        f.repository.publishInbox(
                            f.access,
                            f.conversation,
                            f.room,
                            f.revision(),
                            CollaborationHistoryInboxProposal(f.conversation, version, null),
                        )
                    )
                    .value
            )
        assertEquals(version, receipt.destination)
        assertEquals(
            AppResult.Success(null),
            f.repository.publishInbox(
                f.access,
                f.conversation,
                f.room,
                f.revision(),
                CollaborationHistoryInboxProposal(
                    f.conversation,
                    version.copy(revision = version.revision + 1),
                    null,
                ),
            ),
        )
        assertEquals(index, f.rawInbox())
        assertEquals(source, f.raw())
        assertEquals(
            signal,
            f.database.replicas.checkpoint(
                spaceHistoryScope(f.access),
                CollaborationHistorySignal.PARTITION,
            ),
        )
    }

    @Test
    fun aSourceChangeDuringNoOpConfirmationReturnsConflictWithoutAnIndexWrite() =
        withDatabase { f ->
            f.merge(f.event())
            val revision = f.revision()
            val original = f.repository
            var changed = false
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        if (!changed && partition == spaceInboxPartition(f.access)) {
                            changed = true
                            assertEquals(
                                AppResult.Success(true),
                                original.merge(f.access, f.conversation, f.room, listOf(f.event(2))),
                            )
                        }
                        return f.database.replicas.checkpoint(scope, partition)
                    }
                }
            assertEquals(
                AppResult.Success(null),
                f.repository(store)
                    .publishInbox(
                        f.access,
                        f.conversation,
                        f.room,
                        revision,
                        CollaborationHistoryInboxProposal(f.conversation, null, null),
                    ),
            )
            assertTrue(changed)
            assertNull(f.rawInbox().checkpoint)
        }

    private suspend fun CollaborationSpaceHistoryFixture.revision() =
        assertNotNull(raw().checkpoint).let { it.handle.generation + ":" + it.revision }

    private fun CollaborationSpaceHistoryFixture.createdAt() = event().timestamp - 1000

    private suspend fun CollaborationSpaceHistoryFixture.publish(
        publication: CollaborationSpaceInboxPublication,
        sourceRevision: String? = null,
    ): AppResult<Boolean> {
        val revision = sourceRevision ?: revision()
        return when (
            val result =
                repository.publishInbox(
                    access,
                    conversation,
                    room,
                    revision,
                    CollaborationHistoryInboxProposal(
                        conversation,
                        publication.expected,
                        publication,
                    ),
                )
        ) {
            is AppResult.Failed -> result
            is AppResult.Success -> {
                val receipt = result.value ?: return AppResult.Success(false)
                val source = assertNotNull(raw().checkpoint)
                assertEquals(
                    CollaborationHistorySourceVersion(source.handle.generation, source.revision),
                    receipt.source,
                )
                assertEquals(inbox().version, receipt.destination)
                assertNotNull(receipt.destination)
                AppResult.Success(true)
            }
        }
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
