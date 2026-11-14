package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** Real SQLite/IndexedDB: visibility must not destroy send evidence or resurrect after replay. */
abstract class CollaborationLocalVisibilityStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun deletionSurvivesRestartAndRenewedLoginWithoutDeletingOriginalEvidence() =
        withDatabase { f ->
            f.merge(f.event(1), f.event(2))
            val original = requireNotNull(f.lookup(f.event(2).eventId))
            assertEquals(AppResult.Success(true), f.hide(f.event(2)))
            assertEquals(
                listOf(f.event(1).eventId),
                f.read(limit = 1).messages.map { it.primaryEventId },
            )
            assertNull(f.read(limit = 1).next)
            assertEquals(original.copy(locallyHidden = true), f.lookup(f.event(2).eventId))
            assertEquals(setOf(f.event(2).reference()), f.hidden(f.event(1), f.event(2)))
            val renewed = f.access.copy(sessionId = "renewed-login")
            f.reopen(renewed)
            assertEquals(
                listOf(f.event(1).eventId),
                f.read(renewed).messages.map { it.primaryEventId },
            )
            assertEquals(AppResult.Success(false), f.hide(f.event(1)))
            assertEquals(
                AppResult.Success(setOf(f.event(2).reference())),
                f.repository
                    .observeHidden(renewed, f.conversation, f.room, listOf(f.event(2).reference()))
                    .first(),
            )
            assertEquals(
                AppResult.Success(emptySet()),
                f.repository
                    .observeHidden(
                        renewed.copy(companyId = historyId(8000)),
                        f.conversation,
                        f.room,
                        listOf(f.event(2).reference()),
                    )
                    .first(),
            )
            assertTrue(f.read().messages.isEmpty())
        }

    @Test
    fun hideBeforeHistoryImportKeepsRetriesAndRedactionsHidden() = withDatabase { f ->
        val event = f.event()
        assertEquals(AppResult.Success(true), f.hide(event))
        assertTrue(f.read().messages.isEmpty())
        assertNull(f.lookup())
        f.reopen()
        val retry = event.copy(eventId = "\$retry-hidden", timestamp = event.timestamp + 10)
        f.merge(retry, event)
        assertTrue(f.read().messages.isEmpty())
        assertEquals(setOf(event.reference(), retry.reference()), f.hidden(event, retry))
        assertEquals(2, requireNotNull(f.lookup()).eventIds.size)
        f.merge(CollaborationHistoryChange.Redacted(retry.eventId, retry.sender))
        f.merge(event)
        val retained = requireNotNull(f.lookup())
        assertTrue(retained.locallyHidden && retained.redacted)
        assertNull(retained.text)
        assertTrue(f.read().messages.isEmpty())
    }

    @Test
    fun eventOnlySelectionLearnsOriginalLogicalIdentityAndKeepsOtherSendersVisible() =
        withDatabase { f ->
            val event = f.event()
            f.merge(event)
            assertEquals(
                AppResult.Success(true),
                f.repository.hide(
                    f.access,
                    f.conversation,
                    f.room,
                    listOf(event.reference().copy(logicalId = null)),
                ),
            )
            val retry = event.copy(eventId = "\$next-alias")
            val colleague = event.copy(sender = "@another:example.test", eventId = "\$colleague")
            f.merge(retry, colleague)
            assertEquals(listOf(colleague.eventId), f.read().messages.map { it.primaryEventId })
            assertEquals(setOf(retry.reference()), f.hidden(retry, colleague))
        }

    @Test
    fun bulkDeletionIsBoundedAtomicAndRetryAfterLostCommitResponseIsIdempotent() =
        withDatabase { f ->
            val events = (1..50).map(f::event)
            for (batch in events.chunked(16)) f.merge(*batch.toTypedArray())
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertEquals(2, mutations.size)
                        val source = assertIs<ReplicaMutation.Commit>(mutations.first())
                        assertEquals(assertNotNull(f.raw().checkpoint).handle, source.handle)
                        assertTrue(source.documents.size <= 150)
                        val signal = assertIs<ReplicaMutation.Replace>(mutations.last())
                        assertEquals(CollaborationHistorySignal.PARTITION, signal.partition)
                        assertTrue(f.database.replicas.mutate(scope, mutations, wake))
                        error("Lost local commit response")
                    }
                }
            assertIs<AppResult.Failed>(
                f.repository(store)
                    .hide(f.access, f.conversation, f.room, events.map { it.reference() })
            )
            assertTrue(f.read().messages.isEmpty())
            val before = f.raw().checkpoint
            assertEquals(AppResult.Success(true), f.hide(*events.toTypedArray()))
            assertEquals(before, f.raw().checkpoint)
            assertEquals(50, f.hidden(*events.toTypedArray()).size)
            assertIs<AppResult.Failed>(f.hide(*(1..51).map(f::event).toTypedArray()))
            assertIs<AppResult.Failed>(f.hide(f.event(), f.event()))
            assertIs<AppResult.Failed>(f.hide())
            assertEquals(before, f.raw().checkpoint)
        }

    @Test
    fun conflictingSenderOrLogicalIdentityRollsBackTheEntireSelection() = withDatabase { f ->
        f.merge(f.event(), f.event(2))
        val before = f.raw()
        for (invalid in
            listOf(
                f.event().copy(sender = "@forged:example.test"),
                f.event().copy(logicalId = historyId(99)),
            )) {
            assertIs<AppResult.Failed>(f.hide(f.event(2), invalid))
            assertEquals(before, f.raw())
        }
        assertIs<AppResult.Failed>(
            f.repository.hide(
                f.access,
                f.conversation,
                "!wrong:example.test",
                listOf(f.event().reference()),
            )
        )
        assertEquals(before, f.raw())
    }

    @Test
    fun concurrentHistoryCommitReturnsConflictAndARepeatPreservesBothFacts() = withDatabase { f ->
        f.merge(f.event())
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.merge(f.event(2))
                    return f.database.replicas.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repository(store)
                .hide(f.access, f.conversation, f.room, listOf(f.event().reference())),
        )
        assertEquals(2, f.read().messages.size)
        assertEquals(AppResult.Success(true), f.hide(f.event()))
        assertEquals(listOf(f.event(2).eventId), f.read().messages.map { it.primaryEventId })
    }

    @Test
    fun visibilityChangesNotifyObserversAndNeverExposePlaintextIndexes() = withDatabase { f ->
        val event = f.event()
        val empty = CompletableDeferred<Unit>()
        val hidden = CompletableDeferred<Unit>()
        coroutineScope {
            val observation = launch {
                f.repository
                    .observeHidden(f.access, f.conversation, f.room, listOf(event.reference()))
                    .collect {
                        val value =
                            assertIs<AppResult.Success<Set<CollaborationMessageReference>>>(it)
                                .value
                        if (value.isEmpty()) empty.complete(Unit) else hidden.complete(Unit)
                    }
            }
            empty.await()
            assertNull(f.keys.read())
            assertEquals(AppResult.Success(true), f.hide(event))
            hidden.await()
            observation.cancelAndJoin()
        }
        for (document in f.raw().documents) {
            assertFalse(document.toString().contains(event.eventId))
            assertFalse(document.toString().contains(event.sender))
            assertFalse(document.toString().contains(requireNotNull(event.logicalId)))
        }
    }

    @Test
    fun tamperedHiddenRulesAndUnexpectedHiddenQueryRowsFailClosed() = withDatabase { f ->
        assertEquals(
            AppResult.Success(true),
            f.repository.hide(
                f.access,
                f.conversation,
                f.room,
                listOf(f.event().reference().copy(logicalId = null)),
            ),
        )
        f.merge(f.event())
        val raw = f.raw()
        val rule = raw.documents.first { it.tags == setOf(HISTORY_HIDDEN_RULES) }
        for (altered in
            listOf(
                rule.copy(payload = "corrupt"),
                rule.copy(tags = emptySet()),
                rule.copy(version = requireNotNull(raw.checkpoint).revision + 1),
                rule.copy(sortKey = "wrong"),
            )) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun document(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ) =
                        if (id == rule.id) altered
                        else f.database.replicas.document(scope, partition, id)
                }
            assertIs<AppResult.Failed>(
                f.repository(store)
                    .observeHidden(f.access, f.conversation, f.room, listOf(f.event().reference()))
                    .first()
            )
        }
        val hidden = raw.documents.single { it.tags == setOf(HISTORY_HIDDEN_MESSAGES) }
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) = flowOf(ReplicaQueryPage(raw.checkpoint, listOf(listOf(hidden))))
            }
        assertIs<AppResult.Failed>(
            f.repository(store).observe(f.access, f.conversation, f.room).first()
        )
    }

    @Test
    fun cancellationAndAccountReplacementCannotPartiallyHideSelection() = withDatabase { f ->
        f.merge(f.event(), f.event(2))
        val before = f.raw()
        val entered = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val pending = async {
                f.repository(store)
                    .hide(
                        f.access,
                        f.conversation,
                        f.room,
                        listOf(f.event().reference(), f.event(2).reference()),
                    )
            }
            entered.await()
            pending.cancelAndJoin()
        }
        assertEquals(before, f.raw())
        val replaced =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(f.access.copy(sessionId = "another", accountId = historyId(777)))
                    return f.database.replicas.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repository(replaced)
                .hide(f.access, f.conversation, f.room, listOf(f.event().reference())),
        )
        f.activate()
        assertEquals(before, f.raw())
    }

    @Test
    fun missingKeyCannotReplaceVisibilityOnlyPartitionAndInvalidReadsCreateNothing() =
        withDatabase { f ->
            assertIs<AppResult.Failed>(
                f.repository
                    .observeHidden(
                        f.access,
                        f.conversation,
                        "invalid",
                        listOf(f.event().reference()),
                    )
                    .first()
            )
            assertIs<AppResult.Failed>(
                f.repository
                    .observeHidden(
                        f.access,
                        f.conversation,
                        f.room,
                        (1..257).map { f.event(it).reference() },
                    )
                    .first()
            )
            assertNull(f.keys.read())
            assertEquals(AppResult.Success(true), f.hide(f.event()))
            val before = f.raw()
            f.keys.write(null)
            assertIs<AppResult.Failed>(f.hide(f.event(2)))
            assertIs<AppResult.Failed>(
                f.repository
                    .observeHidden(f.access, f.conversation, f.room, listOf(f.event().reference()))
                    .first()
            )
            assertNull(f.keys.read())
            assertEquals(before, f.raw())
        }

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            block(f)
        } finally {
            f.close()
        }
    }
}

private fun CollaborationHistoryChange.Text.reference() =
    CollaborationMessageReference(sender, eventId, logicalId)

private suspend fun CollaborationHistoryFixture.hide(
    vararg events: CollaborationHistoryChange.Text
) = repository.hide(access, conversation, room, events.map { it.reference() })

private suspend fun CollaborationHistoryFixture.hidden(
    vararg events: CollaborationHistoryChange.Text
) =
    assertIs<AppResult.Success<Set<CollaborationMessageReference>>>(
            repository
                .observeHidden(access, conversation, room, events.map { it.reference() })
                .first()
        )
        .value
