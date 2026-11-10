package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.historyScope
import dev.fajar.starter.collaboration.data.notifications.notificationSettingsPartition
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationNotificationSettingsRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** The same encryption, competing-writer and interruption cases run on SQLite and IndexedDB. */
abstract class CollaborationNotificationSettingsStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun observationStartsWithoutKeyAndIgnoresUnrelatedWritesBeforeFollowingSavedSettings() =
        withDatabase { f ->
            coroutineScope {
                val updates =
                    kotlinx.coroutines.channels.Channel<
                        AppResult<CollaborationNotificationSettingsState>
                    >(
                        8
                    )
                val job = launch {
                    f.repo().observe(f.access, f.conversation).collect { updates.send(it) }
                }
                try {
                    assertEquals(
                        CollaborationNotificationSettingsState(),
                        expect(updates.receive()),
                    )
                    assertNull(f.keys.read())
                    assertTrue(
                        f.database.replicas.replace(
                            historyScope(f.access),
                            "notification-publications-fixture",
                            null,
                            ReplicaSnapshot(
                                historyId(9009),
                                "opaque unrelated checkpoint",
                                emptyList(),
                            ),
                        )
                    )
                    assertTrue(expect(f.repo().update(f.access, f.conversation, change(1))))
                    val saved = expect(updates.receive())
                    assertEquals(change(1).settings, saved.settings)
                    assertTrue(updates.tryReceive().isFailure)
                    val next = change(2, saved.version, CollaborationNotificationMute.Unmuted)
                    assertTrue(expect(f.repo().update(f.access, f.conversation, next)))
                    val latest = expect(updates.receive())
                    assertEquals(next.settings, latest.settings)
                    assertTrue(latest.version!!.revision > saved.version!!.revision)
                    job.cancelAndJoin()
                    f.reopen()
                    assertEquals(latest, expect(f.repo().observe(f.access, f.conversation).first()))
                } finally {
                    withContext(NonCancellable) { job.cancelAndJoin() }
                    updates.close()
                }
            }
        }

    @Test
    fun malformedSettingsEmissionCanRecoverButFailedUpstreamDoesNotRestartItself() =
        withDatabase { f ->
            assertTrue(expect(f.repo().update(f.access, f.conversation, change(1))))
            val raw =
                f.database.replicas.checkpoint(
                    historyScope(f.access),
                    notificationSettingsPartition(f.access, f.conversation),
                )!!
            var subscriptions = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeCheckpoint(scope: ReplicaScope, partition: String) = flow {
                        subscriptions++
                        assertEquals(historyScope(f.access), scope)
                        assertEquals(
                            notificationSettingsPartition(f.access, f.conversation),
                            partition,
                        )
                        emit(raw.copy(payload = "malformed encrypted settings"))
                        emit(raw)
                        emit(raw)
                        error("Fictional observation failure")
                    }
                }
            val values = f.repo(store).observe(f.access, f.conversation).toList()
            assertEquals(3, values.size)
            assertIs<AppResult.Failed>(values[0])
            assertEquals(f.settings(), expect(values[1]))
            assertIs<AppResult.Failed>(values[2])
            assertEquals(1, subscriptions)
        }

    @Test
    fun cancellingSettingsObservationJoinsPendingMappingWithoutALateValue() = withDatabase { f ->
        assertTrue(expect(f.repo().update(f.access, f.conversation, change(1))))
        val entered = CompletableDeferred<Unit>()
        var joined = false
        val partition = notificationSettingsPartition(f.access, f.conversation)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    if (partition != notificationSettingsPartition(f.access, f.conversation))
                        return f.database.replicas.checkpoint(scope, partition)
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        joined = true
                    }
                }
            }
        coroutineScope {
            val values = mutableListOf<AppResult<CollaborationNotificationSettingsState>>()
            val job = launch {
                f.repo(store).observe(f.access, f.conversation).collect { values += it }
            }
            try {
                entered.await()
                job.cancelAndJoin()
                assertTrue(joined)
                assertTrue(values.isEmpty())
                assertNotNull(f.database.replicas.checkpoint(historyScope(f.access), partition))
            } finally {
                withContext(NonCancellable) { job.cancelAndJoin() }
            }
        }
    }

    @Test
    fun defaultsNeedNoKeyAndSavedPrivacySurvivesDatabaseReopenAndReauthentication() =
        withDatabase { f ->
            assertEquals(CollaborationNotificationSettingsState(), f.settings())
            assertNull(f.keys.read())
            val change =
                change(
                    1,
                    null,
                    CollaborationNotificationMute.Until(Instant.parse("2027-01-01T00:00:00Z")),
                )
            assertTrue(expect(f.repo().update(f.access, f.conversation, change)))
            val saved = f.settings()
            val raw =
                requireNotNull(
                    f.database.replicas.checkpoint(
                        historyScope(f.access),
                        notificationSettingsPartition(f.access, f.conversation),
                    )
                )
            for (secret in listOf(change.operationId, "SenderOnly", "until", "2027")) assertFalse(
                raw.payload.contains(secret)
            )
            val renewed = f.access.copy(sessionId = "renewed")
            f.reopen(renewed)
            assertEquals(saved, expect(f.repo().read(renewed, f.conversation)))
            f.activate()
            f.merge(f.event()) // Preferences and original history share the retained key safely.
            assertNotNull(f.lookup())
        }

    @Test
    fun staleAndDuplicateOsActionsCannotUndoNewerSettings() = withDatabase { f ->
        val first = change(1)
        assertTrue(expect(f.repo().update(f.access, f.conversation, first)))
        val old = f.settings()
        assertTrue(expect(f.repo().update(f.access, f.conversation, first)))
        assertEquals(old, f.settings())
        val next = change(2, old.version, CollaborationNotificationMute.Unmuted)
        assertTrue(expect(f.repo().update(f.access, f.conversation, next)))
        val saved = f.settings()
        assertFalse(expect(f.repo().update(f.access, f.conversation, first)))
        assertIs<AppResult.Failed>(
            f.repo().update(f.access, f.conversation, next.copy(settings = first.settings))
        )
        assertEquals(saved, f.settings())
    }

    @Test
    fun actualCommittedWriteWithLostReplyIsRecoveredWithoutNewRevision() = withDatabase { f ->
        val change = change(1)
        val uncertain =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    assertTrue(
                        f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                    )
                    error("Injected reply loss")
                }
            }
        assertIs<AppResult.Failed>(f.repo(uncertain).update(f.access, f.conversation, change))
        f.reopen()
        val saved = f.settings()
        assertEquals(change.operationId, saved.operationId)
        assertTrue(expect(f.repo().update(f.access, f.conversation, change)))
        assertEquals(saved, f.settings())
    }

    @Test
    fun competingFirstWritersShareOneHistoryKeyAndOnlyOneObservedChangeWins() = withDatabase { f ->
        val both = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var entered = 0
        val pending =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    if (++entered == 2) both.complete(Unit)
                    release.await()
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        coroutineScope {
            val a = async { f.repo(pending).update(f.access, f.conversation, change(1)) }
            val b = async {
                f.repo(pending)
                    .update(
                        f.access,
                        f.conversation,
                        change(2, mute = CollaborationNotificationMute.Unmuted),
                    )
            }
            both.await()
            release.complete(Unit)
            assertEquals(listOf(false, true), listOf(expect(a.await()), expect(b.await())).sorted())
        }
        val saved = f.settings()
        f.reopen()
        assertEquals(saved, f.settings())
        assertTrue(expect(f.repo().update(f.access, f.conversation, change(3, saved.version))))
    }

    @Test
    fun missingKeyNeverRegeneratesOverInactiveNotificationRecords() = withDatabase { f ->
        assertTrue(expect(f.repo().update(f.access, f.conversation, change(1))))
        val saved = f.settings()
        val retained = requireNotNull(f.keys.read())
        f.keys.write(null)
        val other = f.access.copy(accountId = historyId(9900))
        f.activate(other)
        assertIs<AppResult.Failed>(f.repo().update(other, f.conversation, change(2)))
        assertNull(f.keys.read())
        f.keys.write(retained)
        f.activate()
        assertEquals(saved, f.settings())
    }

    @Test
    fun accountChangeBeforeActualCommitCannotWriteToEitherOwnersPreferences() = withDatabase { f ->
        assertTrue(expect(f.repo().update(f.access, f.conversation, change(1))))
        val saved = f.settings()
        val other = f.access.copy(accountId = historyId(9901))
        val changed =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    f.activate(other)
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                        wake,
                    )
                }
            }
        assertFalse(
            expect(f.repo(changed).update(f.access, f.conversation, change(2, saved.version)))
        )
        assertEquals(
            CollaborationNotificationSettingsState(),
            expect(f.repo().read(other, f.conversation)),
        )
        f.activate()
        assertEquals(saved, f.settings())
    }

    @Test
    fun cancellationBeforeCommitKeepsPriorPrivacyAndJoinsWork() = withDatabase { f ->
        assertTrue(expect(f.repo().update(f.access, f.conversation, change(1))))
        val saved = f.settings()
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val pending =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            }
        coroutineScope {
            val job = launch {
                f.repo(pending).update(f.access, f.conversation, change(2, saved.version))
            }
            entered.await()
            job.cancelAndJoin()
        }
        assertTrue(stopped)
        f.reopen()
        assertEquals(saved, f.settings())
    }

    @Test
    fun ciphertextCannotMoveAcrossCompanyConversationGenerationOrRevision() = withDatabase { f ->
        assertTrue(expect(f.repo().update(f.access, f.conversation, change(1))))
        val saved = f.settings()
        val raw =
            requireNotNull(
                f.database.replicas.checkpoint(
                    historyScope(f.access),
                    notificationSettingsPartition(f.access, f.conversation),
                )
            )
        val targets =
            listOf(
                f.access to historyId(9902),
                f.access.copy(companyId = historyId(9903)) to f.conversation,
                f.access.copy(accountId = historyId(9904)) to f.conversation,
                f.access.copy(audience = "https://other.example.test/") to f.conversation,
            )
        for ((owner, conversation) in targets) {
            f.activate(owner)
            val forged =
                raw.copy(
                    handle =
                        raw.handle.copy(
                            scope = historyScope(owner),
                            partition = notificationSettingsPartition(owner, conversation),
                        )
                )
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(scope: ReplicaScope, partition: String) = forged
                }
            assertIs<AppResult.Failed>(f.repo(store).read(owner, conversation))
        }
        f.activate()
        for (forged in
            listOf(
                raw.copy(revision = raw.revision + 1),
                raw.copy(handle = raw.handle.copy(generation = historyId(9905))),
                raw.copy(payload = "invalid ciphertext"),
                raw.copy(payload = "x".repeat(8193)),
                raw.copy(published = false),
            )) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(scope: ReplicaScope, partition: String) = forged
                }
            assertIs<AppResult.Failed>(f.repo(store).read(f.access, f.conversation))
        }
        assertEquals(saved, f.settings())
    }

    @Test
    fun invalidMutationCannotCreateAKeyOrReplaceSavedData() = withDatabase { f ->
        val repo = f.repo()
        assertIs<AppResult.Failed>(repo.read(f.access, "invalid"))
        assertIs<AppResult.Failed>(
            repo.update(f.access, f.conversation, change(1).copy(operationId = "invalid"))
        )
        assertIs<AppResult.Failed>(
            repo.update(
                f.access,
                f.conversation,
                change(1)
                    .copy(
                        expected =
                            CollaborationNotificationSettingsRevision(
                                historyId(9910),
                                Long.MAX_VALUE,
                            )
                    ),
            )
        )
        assertNull(f.keys.read())
    }

    private fun CollaborationHistoryFixture.repo(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationNotificationSettingsRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.settings() =
        expect(repo().read(access, conversation))

    private fun change(
        index: Int,
        expected: CollaborationNotificationSettingsRevision? = null,
        mute: CollaborationNotificationMute = CollaborationNotificationMute.Forever,
    ) =
        CollaborationNotificationSettingsChange(
            historyId(index),
            expected,
            CollaborationNotificationSettings(mute, CollaborationNotificationPreview.SenderOnly),
        )

    private fun <T> expect(value: AppResult<T>): T = assertIs<AppResult.Success<T>>(value).value

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val fixture = CollaborationHistoryFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
