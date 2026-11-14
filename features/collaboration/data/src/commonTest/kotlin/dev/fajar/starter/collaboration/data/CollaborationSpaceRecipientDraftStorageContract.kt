package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.recipientDraftPartition
import dev.fajar.starter.collaboration.data.history.spaceHistoryScope
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceRecipientDraftRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRecipientDraftStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun emptyReadsDoNotCreateKeysAndExactTextSurvivesReopen() = withDatabase { f ->
        val draft = f.draft(text = "  before a conversation\n😀  ")
        assertEquals(AppResult.Success(null), f.drafts().get(f.access, draft.peerAccountId))
        assertNull(f.keys.read())
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
        val key = f.keys.read()
        val raw = f.rawDraft(draft.peerAccountId)
        assertFalse(raw.toString().contains(draft.text))
        f.reopen()
        assertEquals(AppResult.Success(draft), f.drafts().get(f.access, draft.peerAccountId))
        assertEquals(key, f.keys.read())
        val renewed = f.access.copy(sessionId = "renewed-recipient-login")
        f.reopen(renewed)
        assertEquals(AppResult.Success(draft), f.drafts().get(renewed, draft.peerAccountId))
        assertEquals(AppResult.Success(null), f.drafts().get(f.access, draft.peerAccountId))
        assertEquals(key, f.keys.read())
    }

    @Test
    fun emptyRevisionAndExactReplayFenceLateWritersWithoutRewriting() = withDatabase { f ->
        val draft = f.draft()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
        val first = f.rawDraft(draft.peerAccountId)
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
        assertEquals(first, f.rawDraft(draft.peerAccountId))
        assertIs<AppResult.Failed>(
            f.drafts().replace(f.access, draft, draft.copy(text = "same revision"))
        )
        val cleared = draft.copy(revision = historyId(9104), text = "")
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, draft, cleared))
        f.reopen()
        assertEquals(AppResult.Success(false), f.drafts().replace(f.access, null, draft))
        assertEquals(
            AppResult.Success(false),
            f.drafts().replace(f.access, draft, draft.copy(revision = historyId(9105))),
        )
        assertEquals(AppResult.Success(cleared), f.drafts().get(f.access, draft.peerAccountId))
    }

    @Test
    fun simultaneousFirstWritersRetainOnlyOneOriginalAndOneKey() = withDatabase { f ->
        val release = CompletableDeferred<Unit>()
        val barrier = Mutex()
        var arrived = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    barrier.withLock { if (++arrived == 2) release.complete(Unit) }
                    release.await()
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        val first = f.draft()
        val second = first.copy(revision = historyId(9104), text = "Competing edit")
        val results = coroutineScope {
            listOf(
                    async { f.drafts(store).replace(f.access, null, first) },
                    async { f.drafts(store).replace(f.access, null, second) },
                )
                .awaitAll()
        }
        assertEquals(1, results.count { it == AppResult.Success(true) })
        assertEquals(1, results.count { it == AppResult.Success(false) })
        val winner =
            assertIs<AppResult.Success<CollaborationSpaceRecipientDraft?>>(
                    f.drafts().get(f.access, first.peerAccountId)
                )
                .value
        assertTrue(winner in listOf(first, second))
        val key = f.keys.read()
        f.reopen()
        assertEquals(AppResult.Success(winner), f.drafts().get(f.access, first.peerAccountId))
        assertEquals(key, f.keys.read())
    }

    @Test
    fun lostWriteReplyReplaysTheExactValueWithoutAnotherRevision() = withDatabase { f ->
        val draft = f.draft()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertTrue(
                        f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                    )
                    error("Lost recipient write reply")
                }
            }
        assertIs<AppResult.Failed>(f.drafts(store).replace(f.access, null, draft))
        val committed = f.rawDraft(draft.peerAccountId)
        f.reopen()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
        assertEquals(committed, f.rawDraft(draft.peerAccountId))
    }

    @Test
    fun independentPeersAndGroupsDoNotOverwriteEachOther() = withDatabase { f ->
        val first = f.draft()
        val second =
            first.copy(
                peerAccountId = historyId(9110),
                revision = historyId(9111),
                text = "Another person",
            )
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, first))
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, second))
        val anotherGroup = f.access.copy(spaceId = historyId(9120))
        assertEquals(AppResult.Success(null), f.drafts().get(anotherGroup, first.peerAccountId))
        val other = first.copy(text = "Another Group", revision = historyId(9121))
        assertEquals(AppResult.Success(true), f.drafts().replace(anotherGroup, null, other))
        assertEquals(AppResult.Success(first), f.drafts().get(f.access, first.peerAccountId))
        assertEquals(AppResult.Success(second), f.drafts().get(f.access, second.peerAccountId))
        assertEquals(AppResult.Success(other), f.drafts().get(anotherGroup, first.peerAccountId))
    }

    @Test
    fun invalidPeerRevisionTextOrTimeNeverCreatesARecordOrKey() = withDatabase { f ->
        val draft = f.draft()
        for (invalid in
            listOf(
                draft.copy(peerAccountId = f.access.accountId),
                draft.copy(peerAccountId = "00000000-0000-0000-0000-000000000000"),
                draft.copy(revision = "invalid"),
                draft.copy(text = "x".repeat(16_385)),
                draft.copy(text = "€".repeat(16_384)),
                draft.copy(text = "\u0000"),
                draft.copy(text = "\uD800"),
                draft.copy(savedAt = Instant.fromEpochMilliseconds(-1)),
            )) assertIs<AppResult.Failed>(f.drafts().replace(f.access, null, invalid))
        assertNull(f.keys.read())
        assertNull(f.rawDraft(draft.peerAccountId).checkpoint)
    }

    @Test
    fun maximumUtf8BodyFitsTheBoundedDocumentAndReopensExactly() = withDatabase { f ->
        val draft = f.draft(text = "é".repeat(16_384))
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
        val raw = f.rawDraft(draft.peerAccountId)
        assertTrue(raw.documents.single().payload!!.length in 32_769..131_072)
        assertTrue(raw.checkpoint!!.payload.length <= 32_768)
        f.reopen()
        assertEquals(AppResult.Success(draft), f.drafts().get(f.access, draft.peerAccountId))
    }

    @Test
    fun losingTheExistingContentKeyNeverGeneratesAReplacementOverOldRecords() = withDatabase { f ->
        val draft = f.draft()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
        val raw = f.rawDraft(draft.peerAccountId)
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.drafts().get(f.access, draft.peerAccountId))
        val differentGroup = f.access.copy(spaceId = historyId(9190))
        assertIs<AppResult.Failed>(f.drafts().replace(differentGroup, null, draft))
        assertNull(f.keys.read())
        assertEquals(raw, f.rawDraft(draft.peerAccountId))
    }

    @Test
    fun cancellingAPendingReadJoinsItsOnlyRawSubscription() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> = flow {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
        coroutineScope {
            val read = async { f.drafts(store).get(f.access, f.draft().peerAccountId) }
            entered.await()
            read.cancelAndJoin()
            assertTrue(stopped.isCompleted)
        }
        assertNull(f.keys.read())
    }

    @Test
    fun substitutedCiphertextIdentityMetadataOrTagsFailWithoutChangingStorage() =
        withDatabase { f ->
            val draft = f.draft()
            assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
            val raw = f.rawDraft(draft.peerAccountId)
            val document = raw.documents.single()
            val checkpoint = requireNotNull(raw.checkpoint)
            val mutations =
                listOf(
                    raw.copy(documents = listOf(document.copy(id = historyId(9191)))),
                    raw.copy(documents = listOf(document.copy(sortKey = historyId(9191)))),
                    raw.copy(documents = listOf(document.copy(version = document.version + 1))),
                    raw.copy(documents = listOf(document.copy(tags = setOf("changed")))),
                    raw.copy(
                        documents = listOf(document.copy(payload = document.payload!!.reversed()))
                    ),
                    raw.copy(
                        checkpoint =
                            checkpoint.copy(
                                handle = checkpoint.handle.copy(generation = historyId(9192))
                            )
                    ),
                    raw.copy(checkpoint = null),
                    raw.copy(documents = listOf(document, document)),
                )
            for (changed in mutations) {
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observeDocument(
                            scope: ReplicaScope,
                            partition: String,
                            id: String,
                        ): Flow<ReplicaPage> = flowOf(changed)
                    }
                assertIs<AppResult.Failed>(f.drafts(store).get(f.access, draft.peerAccountId))
            }
            assertEquals(raw, f.rawDraft(draft.peerAccountId))
        }

    @Test
    fun cancellationAtTheWriteBoundaryKeepsThePreviousRevision() = withDatabase { f ->
        val draft = f.draft()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
        val raw = f.rawDraft(draft.peerAccountId)
        val entered = CompletableDeferred<Unit>()
        var attempts = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    attempts++
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val action = async {
                f.drafts(store)
                    .replace(
                        f.access,
                        draft,
                        draft.copy(revision = historyId(9195), text = "Cancelled"),
                    )
            }
            entered.await()
            action.cancelAndJoin()
        }
        assertEquals(1, attempts)
        assertEquals(raw, f.rawDraft(draft.peerAccountId))
    }

    @Test
    fun otherRecipientContentionStopsAfterThreeAttemptsWithoutOverwritingEitherDraft() =
        withDatabase { f ->
            val first = f.draft()
            assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, first))
            var other: CollaborationSpaceRecipientDraft? = null
            var attempts = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        attempts++
                        val changed =
                            first.copy(
                                peerAccountId = historyId(9170),
                                revision = historyId(9180 + attempts),
                                text = "Concurrent $attempts",
                            )
                        assertEquals(
                            AppResult.Success(true),
                            f.drafts().replace(f.access, other, changed),
                        )
                        other = changed
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
            val result =
                f.drafts(store)
                    .replace(
                        f.access,
                        first,
                        first.copy(revision = historyId(9199), text = "Waiting"),
                    )
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(result).failure.kind)
            assertEquals(3, attempts)
            assertEquals(AppResult.Success(first), f.drafts().get(f.access, first.peerAccountId))
            assertEquals(AppResult.Success(other), f.drafts().get(f.access, other!!.peerAccountId))
        }

    @Test
    fun rawCollectionsAreDetachedBeforeCipherSuspension() = withDatabase { f ->
        val draft = f.draft()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
        val raw = f.rawDraft(draft.peerAccountId)
        val tags = mutableSetOf<String>()
        val documents = mutableListOf(raw.documents.single().copy(tags = tags))
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> = flowOf(raw.copy(documents = documents))
            }
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun decrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    ciphertext: ByteArray,
                ): ByteArray {
                    documents.clear()
                    tags += "late mutation"
                    yield()
                    return f.cipher.decrypt(key, associatedData, ciphertext)
                }
            }
        val repository = EncryptedCollaborationSpaceRecipientDraftRepository(store, cipher, f.keys)
        assertEquals(AppResult.Success(draft), repository.get(f.access, draft.peerAccountId))
        assertEquals(raw, f.rawDraft(draft.peerAccountId))
    }

    private fun CollaborationSpaceHistoryFixture.drafts(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceRecipientDraftRepository(store, cipher, keys)

    private fun CollaborationSpaceHistoryFixture.draft(text: String = "Local recipient draft") =
        CollaborationSpaceRecipientDraft(
            historyId(9102),
            historyId(9103),
            text,
            Instant.fromEpochMilliseconds(1_793_491_200_000),
        )

    private suspend fun CollaborationSpaceHistoryFixture.rawDraft(peer: String) =
        database.replicas
            .observeDocument(spaceHistoryScope(access), recipientDraftPartition(access), peer)
            .first()

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
