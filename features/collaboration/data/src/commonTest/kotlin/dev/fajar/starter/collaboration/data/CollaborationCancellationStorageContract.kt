package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

/** Actual SQLite/IndexedDB arbitration of a local cancellation against immutable SDK attempts. */
abstract class CollaborationCancellationStorageContract {
    protected abstract fun databaseOwner(): CollaborationListDatabaseOwner

    @Test
    fun cancellationReopensWithOriginalIdentityAndNeverBecomesADeliveryReceipt() =
        withDatabase { f ->
            f.insert()
            val original = f.read()
            val oldAccess = f.access
            assertEquals(AppResult.Success(true), f.repo.cancel(f.access, original))
            val cancelled = original.copy(state = CollaborationMessageState.Cancelled)
            assertEquals(cancelled, f.read())
            assertTrue(f.rows().isEmpty())
            assertEquals(AppResult.Success(null), f.repo.readCompletion(f.access, f.intent.id))
            val archived = assertNotNull(f.archive())
            assertFalse(archived.record.request.contains(f.intent.text))
            assertFalse(archived.evidence.contains("CANCELLED"))
            f.reopen(f.access.copy(sessionId = "restored"))
            assertEquals(cancelled, f.read())
            assertEquals(AppResult.Success(null), f.repo.get(oldAccess, f.intent.id))
            assertEquals(AppResult.Success(true), f.repo.cancel(f.access, original))
            f.insert()
            assertTrue(f.rows().isEmpty())
            assertEquals(cancelled, f.read())
            assertIs<AppResult.Failed>(f.repo.insert(f.access, f.intent.copy(text = "Different")))
            assertIs<AppResult.Failed>(
                f.repo.update(f.access, original, CollaborationMessageState.Cancelled)
            )
            assertIs<AppResult.Failed>(
                f.repo.update(f.access, cancelled, CollaborationMessageState.Queued)
            )
            assertIs<AppResult.Failed>(
                f.repo.archiveCompletion(
                    f.access,
                    CollaborationMessageCompletion(cancelled, listOf("$" + "event")),
                )
            )
        }

    @Test
    fun lostArchiveResponseRetriesTheSameDecisionAfterReopen() = withDatabase { f ->
        f.insert()
        val original = f.read()
        val store = f.database.commands
        val lost =
            object : CommandStore by store {
                override suspend fun archive(
                    scope: CommandScope,
                    expected: CommandRecord,
                    evidence: String,
                ): Boolean {
                    assertTrue(store.archive(scope, expected, evidence))
                    error("Fixture lost committed response")
                }
            }
        assertIs<AppResult.Failed>(f.repository(store = lost).cancel(f.access, original))
        f.reopen(f.access.copy(sessionId = "restored"))
        assertEquals(AppResult.Success(true), f.repo.cancel(f.access, original))
        f.insert()
        assertTrue(f.rows().isEmpty())
        assertEquals(original.copy(state = CollaborationMessageState.Cancelled), f.read())
    }

    @Test
    fun stagingWinningTheArchiveRaceRetainsUncertainNativeWork() = withDatabase { f ->
        f.insert()
        val original = f.read()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = f.database.commands
        val held =
            object : CommandStore by store {
                override suspend fun archive(
                    scope: CommandScope,
                    expected: CommandRecord,
                    evidence: String,
                ): Boolean {
                    entered.complete(Unit)
                    release.await()
                    return store.archive(scope, expected, evidence)
                }
            }
        val staging = CollaborationMessageState.Staging(f.device, emptySet())
        coroutineScope {
            val cancellation = async { f.repository(store = held).cancel(f.access, original) }
            try {
                entered.await()
                assertEquals(AppResult.Success(true), f.repo.update(f.access, original, staging))
            } finally {
                release.complete(Unit)
            }
            assertEquals(AppResult.Success(false), cancellation.await())
        }
        assertEquals(original.copy(revision = 1, state = staging), f.read())
        assertEquals(1, f.rows().size)
        assertNull(f.archive())
    }

    @Test
    fun cancellationWinningTheStagingRacePreventsAStagingCommit() = withDatabase { f ->
        f.insert()
        val original = f.read()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = f.database.commands
        val held =
            object : CommandStore by store {
                override suspend fun update(
                    scope: CommandScope,
                    partition: String,
                    id: String,
                    expectedRevision: Long,
                    state: String,
                ): Boolean {
                    entered.complete(Unit)
                    release.await()
                    return store.update(scope, partition, id, expectedRevision, state)
                }
            }
        coroutineScope {
            val staging = async {
                f.repository(store = held)
                    .update(
                        f.access,
                        original,
                        CollaborationMessageState.Staging(f.device, emptySet()),
                    )
            }
            try {
                entered.await()
                assertEquals(AppResult.Success(true), f.repo.cancel(f.access, original))
            } finally {
                release.complete(Unit)
            }
            assertEquals(AppResult.Success(false), staging.await())
        }
        assertEquals(original.copy(state = CollaborationMessageState.Cancelled), f.read())
        assertTrue(f.rows().isEmpty())
        f.insert()
        assertTrue(f.rows().isEmpty())
    }

    @Test
    fun noStartedOrChangedRecordCanBeCancelled() = withDatabase { f ->
        f.insert()
        val original = f.read()
        for (wrong in
            listOf(
                original.copy(intent = f.intent.copy(text = "Changed")),
                original.copy(intent = f.intent.copy(conversationId = messageTestId(99))),
                original.copy(intent = f.intent.copy(id = messageTestId(99))),
            )) assertEquals(AppResult.Success(false), f.repo.cancel(f.access, wrong))
        assertIs<AppResult.Failed>(f.repo.cancel(f.access, original.copy(revision = 1)))
        assertEquals(original, f.read())
        for (state in
            listOf(
                CollaborationMessageState.Staging(f.device, emptySet()),
                CollaborationMessageState.Staged(f.device, "transaction"),
                CollaborationMessageState.Sent(f.device, "transaction", "$" + "event"),
            )) {
            assertEquals(AppResult.Success(true), f.repo.update(f.access, f.read(), state))
            assertEquals(AppResult.Success(false), f.repo.cancel(f.access, original))
            assertIs<AppResult.Failed>(f.repo.cancel(f.access, f.read()))
            assertEquals(state, f.read().state)
            assertNull(f.archive())
        }
    }

    @Test
    fun twoIndependentCancellersRecoverTheSameImmutableDecision() = withDatabase { f ->
        f.insert()
        val original = f.read()
        coroutineScope {
            val attempts = (1..2).map { async { f.repository().cancel(f.access, original) } }
            attempts.forEach { assertEquals(AppResult.Success(true), it.await()) }
        }
        assertTrue(f.rows().isEmpty())
        assertEquals(original.copy(state = CollaborationMessageState.Cancelled), f.read())
    }

    @Test
    fun coroutineCancellationBeforeCommitRetainsTheQueuedMessage() = withDatabase { f ->
        f.insert()
        val original = f.read()
        val entered = CompletableDeferred<Unit>()
        val cipher =
            object : ContentCipher by AesGcmContentCipher() {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val request = launch { f.repository(cipher = cipher).cancel(f.access, original) }
            entered.await()
            request.cancelAndJoin()
        }
        assertEquals(original, f.read())
        assertNull(f.archive())
        assertEquals(AppResult.Success(true), f.repo.cancel(f.access, original))
    }

    @Test
    fun replacingTheActiveAccountDuringEncryptionCannotArchiveItsOldWork() = withDatabase { f ->
        f.insert()
        val original = f.read()
        val access = f.access
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cipher =
            object : ContentCipher by AesGcmContentCipher() {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    entered.complete(Unit)
                    release.await()
                    return AesGcmContentCipher().encrypt(key, associatedData, plaintext)
                }
            }
        coroutineScope {
            val request = async { f.repository(cipher = cipher).cancel(access, original) }
            try {
                entered.await()
                f.activate(access.copy(sessionId = "other", accountId = messageTestId(99)))
            } finally {
                release.complete(Unit)
            }
            assertEquals(AppResult.Success(false), request.await())
        }
        assertIs<AppResult.Failed>(f.repo.cancel(f.access, original))
        f.activate(access.copy(sessionId = "returned"))
        assertEquals(original, f.read())
        assertNull(f.archive())
    }

    @Test
    fun cancellationArchiveCannotRegenerateAMissingKeyOrMoveToAnotherOwner() = withDatabase { f ->
        f.insert()
        val original = f.read()
        assertEquals(AppResult.Success(true), f.repo.cancel(f.access, original))
        val access = f.access
        val key = f.keys.read()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repo.get(f.access, f.intent.id))
        assertIs<AppResult.Failed>(f.repo.readCompletion(f.access, f.intent.id))
        assertIs<AppResult.Failed>(f.repo.cancel(f.access, original))
        f.activate(access.copy(sessionId = "other", accountId = messageTestId(99)))
        assertEquals(AppResult.Success(null), f.repo.get(f.access, f.intent.id))
        assertIs<AppResult.Failed>(
            f.repo.insert(f.access, f.intent.copy(owner = f.access.messageOwner()))
        )
        assertNull(f.keys.read())
        f.keys.write(key)
        f.activate(access.copy(sessionId = "returned"))
        assertEquals(original.copy(state = CollaborationMessageState.Cancelled), f.read())
    }

    @Test
    fun proofCannotBeTransplantedToChangedValidCiphertextOrMetadata() = withDatabase { f ->
        f.insert()
        val original = f.read()
        assertEquals(AppResult.Success(true), f.repo.cancel(f.access, original))
        val archive = assertNotNull(f.archive())
        val store = f.database.commands
        val key = Base64.decode(assertNotNull(f.keys.read()))
        try {
            val codec = CollaborationMessageCodec(f.access, AesGcmContentCipher(), key)
            val newState = codec.state(f.intent.id, 0, CollaborationMessageState.Queued.toDto())
            val newRequest = codec.request(f.intent.copy(text = "Different valid body"))
            for (altered in
                listOf(
                    archive.copy(record = archive.record.copy(state = newState)),
                    archive.copy(record = archive.record.copy(request = newRequest)),
                    archive.copy(record = archive.record.copy(sortKey = "replaced")),
                    archive.copy(record = archive.record.copy(revision = 1)),
                    archive.copy(evidence = Base64.encode(ByteArray(32))),
                )) {
                val replaced =
                    object : CommandStore by store {
                        override suspend fun archived(
                            scope: CommandScope,
                            partition: String,
                            id: String,
                        ) = altered
                    }
                val repo = f.repository(store = replaced)
                assertIs<AppResult.Failed>(repo.get(f.access, f.intent.id))
                assertIs<AppResult.Failed>(repo.readCompletion(f.access, f.intent.id))
                assertIs<AppResult.Failed>(repo.cancel(f.access, original))
            }
        } finally {
            key.fill(0)
        }
        assertEquals(original.copy(state = CollaborationMessageState.Cancelled), f.read())
    }

    @Test
    fun insertionAlreadyInFlightCannotResurrectACancelledId() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = f.database.commands
        val held =
            object : CommandStore by store {
                override suspend fun insert(
                    scope: CommandScope,
                    record: CommandRecord,
                ): CommandInsertResult {
                    entered.complete(Unit)
                    release.await()
                    return store.insert(scope, record)
                }
            }
        coroutineScope {
            val insertion = async { f.repository(store = held).insert(f.access, f.intent) }
            try {
                entered.await()
                f.insert()
                assertEquals(AppResult.Success(true), f.repo.cancel(f.access, f.read()))
            } finally {
                release.complete(Unit)
            }
            assertIs<AppResult.Failed>(insertion.await())
        }
        f.insert()
        assertTrue(f.rows().isEmpty())
        assertEquals(CollaborationMessageState.Cancelled, f.read().state)
    }

    @Test
    fun preventingAbsentReplyNeverPublishesPendingWorkAndSurvivesReauthentication() =
        withDatabase { f ->
            assertEquals(AppResult.Success(true), f.repo.prevent(f.access, f.intent))
            val original = f.read()
            assertEquals(CollaborationMessageState.Cancelled, original.state)
            assertTrue(f.rows().isEmpty())
            val archived = assertNotNull(f.archive())
            assertFalse(archived.record.request.contains(f.intent.text))
            f.reopen(f.access.copy(sessionId = "restored"))
            assertEquals(original, f.read())
            assertEquals(
                AppResult.Success(true),
                f.repo.prevent(
                    f.access,
                    f.intent.copy(createdAt = f.intent.createdAt + kotlin.time.Duration.parse("1h")),
                ),
            )
            assertEquals(archived, f.archive())
            assertEquals(AppResult.Success(null), f.repo.readCompletion(f.access, f.intent.id))
            f.insert()
            assertTrue(f.rows().isEmpty())
            assertEquals(original, f.read())
            assertEquals(
                AppResult.Success(false),
                f.repo.prevent(f.access, f.intent.copy(text = "Different reply")),
            )
        }

    @Test
    fun preventingReplyCannotReplaceActiveQueuedOrStartedMessages() = withDatabase { f ->
        f.insert()
        val original = f.read()
        assertEquals(AppResult.Success(false), f.repo.prevent(f.access, f.intent))
        assertEquals(original, f.read())
        assertNull(f.archive())
    }

    @Test
    fun lostPreventionResponseRetainsPermanentFenceWithoutActiveRow() = withDatabase { f ->
        val store = f.database.commands
        val lost =
            object : CommandStore by store {
                override suspend fun archiveAbsent(
                    scope: CommandScope,
                    record: CommandRecord,
                    evidence: String,
                ): Boolean {
                    assertTrue(store.archiveAbsent(scope, record, evidence))
                    error("Fixture lost prevention response")
                }
            }
        assertIs<AppResult.Failed>(f.repository(store = lost).prevent(f.access, f.intent))
        f.reopen(f.access.copy(sessionId = "restored"))
        assertEquals(AppResult.Success(true), f.repo.prevent(f.access, f.intent))
        assertEquals(CollaborationMessageState.Cancelled, f.read().state)
        assertTrue(f.rows().isEmpty())
    }

    @Test
    fun preventionWinningAgainstAnInflightInsertionNeverQueuesTheReply() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = f.database.commands
        val held =
            object : CommandStore by store {
                override suspend fun insert(
                    scope: CommandScope,
                    record: CommandRecord,
                ): CommandInsertResult {
                    entered.complete(Unit)
                    release.await()
                    return store.insert(scope, record)
                }
            }
        coroutineScope {
            val insertion = async { f.repository(store = held).insert(f.access, f.intent) }
            try {
                entered.await()
                assertEquals(AppResult.Success(true), f.repo.prevent(f.access, f.intent))
            } finally {
                release.complete(Unit)
            }
            assertIs<AppResult.Failed>(insertion.await())
        }
        assertEquals(CollaborationMessageState.Cancelled, f.read().state)
        assertTrue(f.rows().isEmpty())
    }

    @Test
    fun insertionWinningAgainstPreventionPreservesItsExactPendingRow() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = f.database.commands
        val held =
            object : CommandStore by store {
                override suspend fun archiveAbsent(
                    scope: CommandScope,
                    record: CommandRecord,
                    evidence: String,
                ): Boolean {
                    entered.complete(Unit)
                    release.await()
                    return store.archiveAbsent(scope, record, evidence)
                }
            }
        coroutineScope {
            val prevention = async { f.repository(store = held).prevent(f.access, f.intent) }
            try {
                entered.await()
                f.insert()
            } finally {
                release.complete(Unit)
            }
            assertEquals(AppResult.Success(false), prevention.await())
        }
        assertEquals(CollaborationMessageState.Queued, f.read().state)
        assertEquals(1, f.rows().size)
        assertNull(f.archive())
    }

    @Test
    fun cancellationAfterPreventionCommitJoinsBeforeRetryAndPreservesProof() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        val store = f.database.commands
        val held =
            object : CommandStore by store {
                override suspend fun archiveAbsent(
                    scope: CommandScope,
                    record: CommandRecord,
                    evidence: String,
                ): Boolean {
                    assertTrue(store.archiveAbsent(scope, record, evidence))
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val job = launch { f.repository(store = held).prevent(f.access, f.intent) }
            entered.await()
            job.cancelAndJoin()
        }
        f.reopen(f.access.copy(sessionId = "restored"))
        assertEquals(AppResult.Success(true), f.repo.prevent(f.access, f.intent))
        assertEquals(CollaborationMessageState.Cancelled, f.read().state)
        assertTrue(f.rows().isEmpty())
    }

    private suspend fun MessageStorageFixture.archive() =
        database.commands.archived(messageScope(access), messagePartition(access), intent.id)

    private fun withDatabase(block: suspend (MessageStorageFixture) -> Unit) = runTest {
        val owner = databaseOwner()
        val f = MessageStorageFixture(owner)
        try {
            f.activate(f.access)
            block(f)
        } finally {
            f.database.close()
            owner.dispose()
        }
    }
}
