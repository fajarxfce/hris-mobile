package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

abstract class CollaborationCompanyHistoryInboxStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun companySnapshotsRemainSeparateFromEqualGroupCoordinatesAcrossReopen() = withDatabase { f ->
        f.merge()
        f.base.merge(f.event.copy(text = "Different Group content", contentHash = "b".repeat(64)))
        val expected = f.snapshot()
        assertEquals(f.event.text, expected.events.single().original?.text)
        assertEquals("Different Group content", assertNotNull(f.base.lookup()).text)
        f.base.reopen()
        assertEquals(expected, f.snapshot())
    }

    @Test
    fun actualCompanyAliasesEditsAndLocalHidingKeepTheOriginalSource() = withDatabase { f ->
        f.merge()
        assertEquals(
            AppResult.Success(true),
            f.repository()
                .merge(
                    f.source.access,
                    f.source.conversationId,
                    f.source.roomId,
                    listOf(f.event.copy(eventId = "\$alias")),
                ),
        )
        val edit =
            CollaborationMessageEdit(
                "\$edit",
                f.event.sender,
                f.event.eventId,
                f.event.timestamp + 1,
                "Edited",
                "b".repeat(64),
                originalLogicalId = f.event.logicalId,
            )
        assertEquals(
            AppResult.Success(true),
            f.repository()
                .mergeEdits(f.source.access, f.source.conversationId, f.source.roomId, listOf(edit)),
        )
        assertEquals(
            AppResult.Success(true),
            f.repository()
                .hide(
                    f.source.access,
                    f.source.conversationId,
                    f.source.roomId,
                    listOf(
                        CollaborationMessageReference(
                            f.event.sender,
                            f.event.eventId,
                            f.event.logicalId,
                        )
                    ),
                ),
        )
        val messages =
            f.snapshot(listOf(f.event.eventId, "\$alias", edit.eventId)).events.map {
                assertNotNull(it.original)
            }
        assertEquals(1, messages.distinct().size)
        assertEquals(listOf(f.event.eventId, "\$alias"), messages.first().eventIds)
        assertEquals("Edited", messages.first().latestEdit?.edit?.text)
        assertTrue(messages.first().locallyHidden)
        assertEquals(f.event.text, messages.first().text)
    }

    @Test
    fun publicationUsesCanonicalTargetIdWithoutCopyingOrRewritingCompanyHistory() =
        withDatabase { f ->
            f.merge()
            val raw = f.rawSource()
            val proposal = f.proposal()
            assertNotEquals(f.source.conversationId, proposal.entry.conversationId)
            assertEquals(AppResult.Success(true), f.publish(proposal))
            assertEquals(raw, f.rawSource())
            assertNull(f.base.raw().checkpoint)
            assertEquals(proposal.entry, f.inbox().entry)
            val noWrites =
                object : ReplicaStore by f.base.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean = error("Replay cannot write")
                }
            assertEquals(
                AppResult.Success(true),
                f.publish(f.proposal(), repository = f.repository(noWrites)),
            )
            f.base.reopen()
            assertEquals(proposal.entry, f.inbox().entry)
            assertEquals(raw, f.rawSource())
        }

    @Test
    fun concurrentSourceOrDestinationMutationRejectsTheStalePreviewAndPreservesTyping() =
        withDatabase { f ->
            f.merge()
            val proposal = f.proposal()
            val sourceRace =
                object : ReplicaStore by f.base.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertIs<ReplicaMutation.Check>(mutations.first())
                        assertEquals(
                            AppResult.Success(true),
                            f.repository()
                                .merge(
                                    f.source.access,
                                    f.source.conversationId,
                                    f.source.roomId,
                                    listOf(f.base.event(2)),
                                ),
                        )
                        return f.base.database.replicas.mutate(scope, mutations, wake)
                    }
                }
            assertEquals(
                AppResult.Success(false),
                f.publish(proposal, repository = f.repository(sourceRace)),
            )
            assertNull(f.inbox().entry)
            val draft =
                CollaborationSpaceInboxDraft(
                    CollaborationDraft(
                        f.target,
                        historyId(9900),
                        "Still typing",
                        Instant.fromEpochMilliseconds(f.event.timestamp + 1),
                    ),
                    CollaborationSpaceInboxPosition(f.event.timestamp + 1, 1),
                )
            val draftProposal =
                CollaborationSpaceInboxPublication(
                    null,
                    1,
                    collaborationSpaceInboxEntry(f.target, f.event.timestamp - 1000, draft, null),
                    draft = draft,
                )
            val indexRace =
                object : ReplicaStore by f.base.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertEquals(
                            AppResult.Success(true),
                            f.index().publish(f.base.access, draftProposal),
                        )
                        return f.base.database.replicas.mutate(scope, mutations, wake)
                    }
                }
            assertEquals(
                AppResult.Success(false),
                f.publish(proposal, repository = f.repository(indexRace)),
            )
            assertEquals(draft, f.inbox().draft)
            assertEquals(AppResult.Success(true), f.publish(f.proposal()))
            assertEquals(draft, f.inbox().draft)
        }

    @Test
    fun missingKeysWrongRoomsAndForeignAccountsCannotPublishOrReplaceKeys() = withDatabase { f ->
        f.merge()
        val proposal = f.proposal()
        val revision = assertNotNull(f.snapshot().revision)
        val key = f.base.keys.read()
        assertIs<AppResult.Failed>(
            f.repository()
                .snapshot(f.source.copy(roomId = "!wrong:example.test"), listOf(f.event.eventId))
        )
        assertIs<AppResult.Failed>(
            f.repository()
                .publishInbox(
                    f.base.access.copy(accountId = historyId(9999)),
                    f.source,
                    revision,
                    CollaborationHistoryInboxProposal(f.target, proposal.expected, proposal),
                )
        )
        assertEquals(key, f.base.keys.read())
        f.base.keys.write(null)
        assertIs<AppResult.Failed>(f.repository().snapshot(f.source, listOf(f.event.eventId)))
        assertIs<AppResult.Failed>(
            f.repository()
                .publishInbox(
                    f.base.access,
                    f.source,
                    revision,
                    CollaborationHistoryInboxProposal(f.target, proposal.expected, proposal),
                )
        )
        assertNull(f.base.keys.read())
        assertNull(
            f.base.database.replicas.checkpoint(
                spaceHistoryScope(f.base.access),
                spaceInboxPartition(f.base.access),
            )
        )
    }

    @Test
    fun sourceChangedDuringABoundedReadReturnsChangedInsteadOfMixedContent() = withDatabase { f ->
        f.merge()
        var changed = false
        val store =
            object : ReplicaStore by f.base.database.replicas {
                override suspend fun workingDocuments(
                    scope: ReplicaScope,
                    partition: String,
                    ids: List<String>,
                ): ReplicaPage {
                    val before = f.base.database.replicas.workingDocuments(scope, partition, ids)
                    if (!changed) {
                        changed = true
                        assertEquals(
                            AppResult.Success(true),
                            f.repository()
                                .merge(
                                    f.source.access,
                                    f.source.conversationId,
                                    f.source.roomId,
                                    listOf(f.base.event(2)),
                                ),
                        )
                    }
                    return before
                }
            }
        assertEquals(
            AppResult.Success(null),
            f.repository(store).snapshot(f.source, listOf(f.event.eventId)),
        )
        assertTrue(changed)
        assertNotNull(f.snapshot().events.single().original)
    }

    @Test
    fun cancellationJoinsALateCompanyPublicationAndItsReplayNeedsNoWrite() = withDatabase { f ->
        f.merge()
        val raw = f.rawSource()
        val proposal = f.proposal()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.base.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean =
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                        f.base.database.replicas.mutate(scope, mutations, wake)
                    }
            }
        coroutineScope {
            var reported = false
            val operation = launch {
                f.publish(proposal, repository = f.repository(store))
                reported = true
            }
            entered.await()
            operation.cancel()
            try {
                assertFalse(operation.isCompleted)
            } finally {
                release.complete(Unit)
            }
            operation.join()
            assertFalse(reported)
            assertTrue(operation.isCancelled)
        }
        assertEquals(proposal.entry, f.inbox().entry)
        assertEquals(raw, f.rawSource())
        f.base.reopen()
        assertEquals(AppResult.Success(true), f.publish(f.proposal()))
    }

    @Test
    fun missingSourceAndInvalidInputDoNotCreateHistoryOrDestinationKeys() = withDatabase { f ->
        val value = f.snapshot()
        assertNull(value.revision)
        assertNull(value.events.single().original)
        assertNull(f.base.keys.read())
        val proposal = f.proposal()
        assertEquals(
            AppResult.Success(null),
            f.repository()
                .publishInbox(
                    f.base.access,
                    f.source,
                    "absent",
                    CollaborationHistoryInboxProposal(f.target, proposal.expected, proposal),
                ),
        )
        assertIs<AppResult.Failed>(f.repository().snapshot(f.source, emptyList()))
        assertIs<AppResult.Failed>(
            f.repository().snapshot(f.source, listOf(f.event.eventId, f.event.eventId))
        )
        assertNull(f.base.keys.read())
        assertNull(f.rawSource().checkpoint)
        assertNull(f.inbox().entry)
    }

    @Test
    fun alternatingAbsentPartitionsCannotConfirmAnEmptyPair() = withDatabase { f ->
        val database = f.base.database
        val scope = historyScope(f.source.access)
        val sourcePartition = historyPartition(f.source.access, f.source.conversationId)
        val indexPartition = spaceInboxPartition(f.base.access)
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
                    f.base.access,
                    f.source,
                    null,
                    CollaborationHistoryInboxProposal(f.target, null, null),
                ),
        )
        assertTrue(swaps >= 1)
        assertNull(f.base.keys.read())
        assertTrue(
            database.replicas.checkpoint(scope, sourcePartition) != null ||
                database.replicas.checkpoint(scope, indexPartition) != null
        )
    }

    @Test
    fun anAbsentPairReturnsAnAbsentReceiptWithoutCreatingKeys() = withDatabase { f ->
        val value =
            f.repository()
                .publishInbox(
                    f.base.access,
                    f.source,
                    null,
                    CollaborationHistoryInboxProposal(f.target, null, null),
                )
        assertEquals(AppResult.Success(CollaborationHistoryInboxReceipt(null, null)), value)
        assertNull(f.base.keys.read())
        assertNull(f.rawSource().checkpoint)
        assertNull(f.indexRaw().checkpoint)
    }

    @Test
    fun sourceOnlyConfirmationReturnsItsVersionWithoutCreatingAnIndex() = withDatabase { f ->
        f.merge()
        val before = f.rawSource()
        val source = assertNotNull(before.checkpoint)
        val value =
            assertIs<AppResult.Success<CollaborationHistoryInboxReceipt?>>(
                    f.repository()
                        .publishInbox(
                            f.base.access,
                            f.source,
                            assertNotNull(f.snapshot().revision),
                            CollaborationHistoryInboxProposal(f.target, null, null),
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
        assertEquals(before, f.rawSource())
        assertNull(f.indexRaw().checkpoint)
    }

    @Test
    fun committedReceiptUsesTheWrittenGenerationEvenIfTheIndexIsRemovedBeforeTheReply() =
        withDatabase { f ->
            f.merge()
            val source = f.rawSource()
            val proposal = f.proposal()
            var written: CollaborationSpaceInboxVersion? = null
            val store =
                object : ReplicaStore by f.base.database.replicas {
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
                        assertTrue(f.base.database.replicas.mutate(scope, mutations, wake))
                        val partition = spaceInboxPartition(f.base.access)
                        val current =
                            assertNotNull(f.base.database.replicas.checkpoint(scope, partition))
                        assertTrue(
                            f.base.database.replicas.replace(scope, partition, current, null)
                        )
                        return true
                    }
                }
            val receipt =
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationHistoryInboxReceipt?>>(
                            f.repository(store)
                                .publishInbox(
                                    f.base.access,
                                    f.source,
                                    assertNotNull(f.snapshot().revision),
                                    CollaborationHistoryInboxProposal(
                                        f.target,
                                        proposal.expected,
                                        proposal,
                                    ),
                                )
                        )
                        .value
                )
            assertEquals(assertNotNull(written), receipt.destination)
            assertNull(f.indexRaw().checkpoint)
            assertEquals(source, f.rawSource())
        }

    @Test
    fun checkedNoOpPreservesTheIndexAndSourceSignalAndRejectsAStaleVersion() = withDatabase { f ->
        f.merge()
        assertEquals(AppResult.Success(true), f.publish(f.proposal()))
        val version = assertNotNull(f.inbox().version)
        val index = f.indexRaw()
        val source = f.rawSource()
        val signal =
            f.base.database.replicas.checkpoint(
                spaceHistoryScope(f.base.access),
                CollaborationHistorySignal.PARTITION,
            )
        val receipt =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationHistoryInboxReceipt?>>(
                        f.repository()
                            .publishInbox(
                                f.base.access,
                                f.source,
                                assertNotNull(f.snapshot().revision),
                                CollaborationHistoryInboxProposal(f.target, version, null),
                            )
                    )
                    .value
            )
        assertEquals(version, receipt.destination)
        assertEquals(
            AppResult.Success(null),
            f.repository()
                .publishInbox(
                    f.base.access,
                    f.source,
                    assertNotNull(f.snapshot().revision),
                    CollaborationHistoryInboxProposal(
                        f.target,
                        version.copy(revision = version.revision + 1),
                        null,
                    ),
                ),
        )
        assertEquals(index, f.indexRaw())
        assertEquals(source, f.rawSource())
        assertEquals(
            signal,
            f.base.database.replicas.checkpoint(
                spaceHistoryScope(f.base.access),
                CollaborationHistorySignal.PARTITION,
            ),
        )
    }

    @Test
    fun aSourceChangeDuringNoOpConfirmationReturnsConflictWithoutAnIndexWrite() =
        withDatabase { f ->
            f.merge()
            val revision = assertNotNull(f.snapshot().revision)
            val original = f.repository()
            var changed = false
            val store =
                object : ReplicaStore by f.base.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        if (!changed && partition == spaceInboxPartition(f.base.access)) {
                            changed = true
                            assertEquals(
                                AppResult.Success(true),
                                original.merge(
                                    f.source.access,
                                    f.source.conversationId,
                                    f.source.roomId,
                                    listOf(
                                        f.event.copy(
                                            eventId = "\$receipt-competitor",
                                            logicalId = historyId(9876),
                                        )
                                    ),
                                ),
                            )
                        }
                        return f.base.database.replicas.checkpoint(scope, partition)
                    }
                }
            assertEquals(
                AppResult.Success(null),
                f.repository(store)
                    .publishInbox(
                        f.base.access,
                        f.source,
                        revision,
                        CollaborationHistoryInboxProposal(f.target, null, null),
                    ),
            )
            assertTrue(changed)
            assertNull(f.indexRaw().checkpoint)
        }

    private fun <T> success(value: AppResult<T>) = assertIs<AppResult.Success<T>>(value).value

    private inner class Fixture(owner: CollaborationHistoryDatabaseOwner) {
        val base = CollaborationSpaceHistoryFixture(owner)
        val source =
            CollaborationSpaceHistoryLocation.Original(
                base.legacyAccess,
                base.conversation,
                base.room,
            )
        val target = historyId(9010)
        val event = base.event()

        fun repository(store: ReplicaStore = base.database.replicas) =
            EncryptedCollaborationHistoryRepository(store, base.cipher, base.keys)

        fun index() =
            EncryptedCollaborationSpaceInboxRepository(
                base.database.replicas,
                base.cipher,
                base.keys,
            )

        suspend fun merge() {
            assertEquals(
                AppResult.Success(true),
                repository()
                    .merge(source.access, source.conversationId, source.roomId, listOf(event)),
            )
        }

        suspend fun snapshot(ids: List<String> = listOf(event.eventId)) =
            assertNotNull(success(repository().snapshot(source, ids)))

        suspend fun rawSource() =
            base.database.replicas
                .observe(
                    historyScope(source.access),
                    historyPartition(source.access, source.conversationId),
                    limit = 100,
                )
                .first()

        suspend fun inbox() = success(index().conversation(base.access, target))

        suspend fun indexRaw() =
            base.database.replicas
                .observe(
                    spaceHistoryScope(base.access),
                    spaceInboxPartition(base.access),
                    limit = 100,
                )
                .first()

        suspend fun publish(
            value: CollaborationSpaceInboxPublication,
            repository: EncryptedCollaborationHistoryRepository = repository(),
        ): AppResult<Boolean> {
            return when (
                val result =
                    repository.publishInbox(
                        base.access,
                        source,
                        assertNotNull(snapshot().revision),
                        CollaborationHistoryInboxProposal(target, value.expected, value),
                    )
            ) {
                is AppResult.Failed -> result
                is AppResult.Success -> {
                    val receipt = result.value ?: return AppResult.Success(false)
                    val raw = assertNotNull(rawSource().checkpoint)
                    assertEquals(
                        CollaborationHistorySourceVersion(raw.handle.generation, raw.revision),
                        receipt.source,
                    )
                    assertEquals(inbox().version, receipt.destination)
                    assertNotNull(receipt.destination)
                    AppResult.Success(true)
                }
            }
        }

        suspend fun proposal(): CollaborationSpaceInboxPublication {
            val current = inbox()
            val identity =
                CollaborationSpaceInboxSource.History(
                    source.access.companyId,
                    source.conversationId,
                    source.roomId,
                    event.sender,
                    event.eventId,
                )
            val existing =
                success(index().activities(base.access, target, listOf(identity)))
                    .values
                    .singleOrNull()
            val ordinal = current.version?.lastOrdinal ?: 0L
            val activity =
                CollaborationSpaceInboxActivity(
                    identity,
                    existing?.position
                        ?: CollaborationSpaceInboxPosition(event.timestamp, ordinal + 1),
                    CollaborationSpaceInboxPreview(
                        CollaborationSpaceInboxContent.TEXT,
                        event.text,
                        event.timestamp,
                        null,
                    ),
                )
            return CollaborationSpaceInboxPublication(
                current.version,
                ordinal + if (existing == null) 1 else 0,
                collaborationSpaceInboxEntry(
                    target,
                    event.timestamp - 1000,
                    current.draft,
                    activity,
                ),
                activities = listOf(activity),
            )
        }
    }

    private fun withDatabase(block: suspend (Fixture) -> Unit) = runTest {
        val f = Fixture(databaseOwner())
        try {
            f.base.activate()
            block(f)
        } finally {
            f.base.close()
        }
    }
}
