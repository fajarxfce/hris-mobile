package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRecipientVersionStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun typingPreservesTheLogicalVersionAndCiphertextWhileOriginalMutationsAdvanceIt() =
        withDatabase { f ->
            val sent = f.submission()
            assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, sent.original))
            val empty = f.version()
            assertNull(empty.revision)
            val marker = f.marker()
            var editor = sent.original
            repeat(3) { n ->
                val next = editor.copy(revision = historyId(9400 + n), text = "Typing $n")
                assertEquals(AppResult.Success(true), f.drafts().replace(f.access, editor, next))
                editor = next
                assertEquals(empty, f.version())
                assertEquals(marker.documents, f.marker().documents)
            }
            val captured =
                sent.copy(
                    original = editor.copy(revision = historyId(9500)),
                    nextRevision = historyId(9501),
                )
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, editor, captured),
            )
            val retained = f.version()
            assertEquals(empty.generation, retained.generation)
            assertEquals(f.marker().checkpoint!!.revision, retained.revision)
            assertNotEquals(empty, retained)
            assertEquals(AppResult.Success(true), f.outbox().remove(f.access, captured))
            assertTrue(f.version().revision!! > retained.revision!!)
            assertEquals(AppResult.Success(false), f.outbox().remove(f.access, captured))
        }

    @Test
    fun oneRealObserverIgnoresProcessedTypingAndEmitsEachOriginalChange() = withDatabase { f ->
        withContext(Dispatchers.Default) {
            val results =
                Channel<AppResult<CollaborationSpaceRecipientOutboxVersion?>>(Channel.UNLIMITED)
            val decoded = Channel<Unit>(Channel.UNLIMITED)
            val cipher =
                object : ContentCipher by f.cipher {
                    override suspend fun decrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        ciphertext: ByteArray,
                    ): ByteArray {
                        val result = f.cipher.decrypt(key, associatedData, ciphertext)
                        if (
                            associatedData
                                .decodeToString()
                                .contains("hris-space-recipient-outbox-version-v1")
                        )
                            decoded.send(Unit)
                        return result
                    }
                }
            val job = launch {
                f.outbox(cipher = cipher).observeVersion(f.access).collect { results.send(it) }
            }
            try {
                assertEquals(AppResult.Success(null), withTimeout(10_000) { results.receive() })
                val sent = f.submission()
                assertEquals(
                    AppResult.Success(true),
                    f.drafts().replace(f.access, null, sent.original),
                )
                withTimeout(10_000) { decoded.receive() }
                val initial = withTimeout(10_000) { results.receive() }
                assertNull(
                    assertIs<AppResult.Success<CollaborationSpaceRecipientOutboxVersion?>>(initial)
                        .value!!
                        .revision
                )
                var editor = sent.original
                repeat(3) { n ->
                    val next = editor.copy(revision = historyId(9450 + n), text = "Typed $n")
                    assertEquals(
                        AppResult.Success(true),
                        f.drafts().replace(f.access, editor, next),
                    )
                    editor = next
                    withTimeout(10_000) { decoded.receive() }
                }
                val captured =
                    sent.copy(
                        original = editor.copy(revision = historyId(9510)),
                        nextRevision = historyId(9511),
                    )
                assertEquals(
                    AppResult.Success(CollaborationComposerHandoffResult.STORED),
                    f.outbox().handoff(f.access, editor, captured),
                )
                val changed = withTimeout(10_000) { results.receive() }
                assertEquals(AppResult.Success(f.version()), changed)
                assertNotEquals(initial, changed)
                assertEquals(AppResult.Success(true), f.outbox().remove(f.access, captured))
                assertEquals(
                    AppResult.Success(f.version()),
                    withTimeout(10_000) { results.receive() },
                )
            } finally {
                job.cancelAndJoin()
                results.cancel()
                decoded.cancel()
            }
        }
    }

    @Test
    fun legacyOriginalsRemainDiscoverableAndEditorUpgradePreservesTheirSourceToken() =
        withDatabase { f ->
            val sent = f.legacy()
            val before = f.version()
            assertEquals(0L, before.revision)
            assertTrue(f.marker().documents.isEmpty())
            val original =
                f.database.replicas
                    .observeDocument(
                        spaceHistoryScope(f.access),
                        recipientDraftPartition(f.access),
                        recipientSubmissionId(sent.id),
                    )
                    .first()
                    .documents
            val later =
                sent.nextDraft.copy(revision = historyId(9550), text = "Independent legacy typing")
            assertEquals(
                AppResult.Success(true),
                f.drafts().replace(f.access, sent.nextDraft, later),
            )
            assertEquals(before, f.version())
            assertEquals(1, f.marker().documents.size)
            assertEquals(
                original,
                f.database.replicas
                    .observeDocument(
                        spaceHistoryScope(f.access),
                        recipientDraftPartition(f.access),
                        recipientSubmissionId(sent.id),
                    )
                    .first()
                    .documents,
            )
            assertEquals(
                listOf(sent),
                assertIs<AppResult.Success<CollaborationSpaceRecipientSubmissionPage>>(
                        f.outbox().pending(f.access)
                    )
                    .value
                    .submissions,
            )
            assertEquals(AppResult.Success(true), f.outbox().remove(f.access, sent))
            assertTrue(f.version().revision!! > before.revision!!)
            assertEquals(AppResult.Success(later), f.drafts().get(f.access, later.peerAccountId))
        }

    @Test
    fun exactReplayAndFailedEditorComparisonDoNotChangeTheMarkerOrWake() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val before = f.marker()
        val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
            f.outbox().handoff(f.access, null, sent),
        )
        assertEquals(
            AppResult.Success(false),
            f.drafts().replace(f.access, null, sent.original.copy(revision = historyId(9560))),
        )
        assertEquals(before, f.marker())
        assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun interruptedHandoffRetainsSourceMarkerEditorAndWakeTogetherAcrossRestart() =
        withDatabase { f ->
            val sent = f.submission()
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
                        error("Lost committed reply")
                    }
                }
            assertIs<AppResult.Failed>(f.outbox(store).handoff(f.access, null, sent))
            val marker = f.marker()
            val version = f.version()
            f.reopen()
            assertEquals(marker, f.marker())
            assertEquals(version, f.version())
            assertEquals(AppResult.Success(sent), f.outbox().get(f.access, sent.id))
            assertEquals(
                AppResult.Success(sent.nextDraft),
                f.drafts().get(f.access, sent.original.peerAccountId),
            )
            assertNotNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        }

    @Test
    fun markerCorruptionCannotHideBehindTypingOrRetireAnOriginal() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val raw = f.marker()
        val document = raw.documents.single()
        for (changed in
            listOf(
                raw.copy(documents = listOf(document.copy(payload = "broken"))),
                raw.copy(documents = listOf(document.copy(payload = null))),
                raw.copy(documents = listOf(document, document)),
                raw.copy(documents = listOf(document.copy(tags = setOf(RECIPIENT_OUTBOX_TAG)))),
                raw.copy(documents = listOf(document.copy(version = document.version + 1))),
                raw.copy(checkpoint = null),
            )) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ) =
                        if (id == RECIPIENT_OUTBOX_VERSION_ID) flowOf(changed)
                        else f.database.replicas.observeDocument(scope, partition, id)

                    override suspend fun workingDocuments(
                        scope: ReplicaScope,
                        partition: String,
                        ids: List<String>,
                    ): ReplicaPage {
                        val current = f.database.replicas.workingDocuments(scope, partition, ids)
                        return current.copy(
                            checkpoint = changed.checkpoint,
                            documents =
                                current.documents.filter { it.id != RECIPIENT_OUTBOX_VERSION_ID } +
                                    changed.documents,
                        )
                    }
                }
            assertIs<AppResult.Failed>(f.outbox(store).observeVersion(f.access).first())
            assertIs<AppResult.Failed>(f.outbox(store).remove(f.access, sent))
            assertIs<AppResult.Failed>(
                f.drafts(store)
                    .replace(
                        f.access,
                        sent.nextDraft,
                        sent.nextDraft.copy(revision = historyId(9561), text = "Pending typing"),
                    )
            )
        }
        assertEquals(raw, f.marker())
        assertEquals(AppResult.Success(sent), f.outbox().get(f.access, sent.id))
    }

    @Test
    fun missingContentKeyNeverAllocatesAReplacementForTheMarker() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val marker = f.marker()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.outbox().observeVersion(f.access).first())
        assertIs<AppResult.Failed>(f.outbox().remove(f.access, sent))
        assertNull(f.keys.read())
        assertEquals(marker, f.marker())
    }

    @Test
    fun markerReadsDetachMutableAdapterCollectionsBeforeDecrypting() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val raw = f.marker()
        val expected = f.version()
        val tags = mutableSetOf<String>()
        val documents = mutableListOf(raw.documents.single().copy(tags = tags))
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(scope: ReplicaScope, partition: String, id: String) =
                    flowOf(raw.copy(documents = documents))
            }
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun decrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    ciphertext: ByteArray,
                ): ByteArray {
                    documents.clear()
                    tags += "late change"
                    yield()
                    return f.cipher.decrypt(key, associatedData, ciphertext)
                }
            }
        assertEquals(
            AppResult.Success(expected),
            f.outbox(store, cipher).observeVersion(f.access).first(),
        )
    }

    @Test
    fun cancellationClosesTheOnlyMarkerSubscriptionWithoutCreatingAKey() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        var stopped = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> = flow {
                    assertEquals(RECIPIENT_OUTBOX_VERSION_ID, id)
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped++
                    }
                }
            }
        coroutineScope {
            val job = launch { f.outbox(store).observeVersion(f.access).collect() }
            entered.await()
            job.cancelAndJoin()
        }
        assertEquals(1, stopped)
        assertNull(f.keys.read())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun anotherGroupOrReplacementAccountCannotReadTheMarker() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val before = f.version()
        assertEquals(
            AppResult.Success(null),
            f.outbox().observeVersion(f.access.copy(spaceId = historyId(9580))).first(),
        )
        val other = f.access.copy(accountId = historyId(9581), sessionId = "other")
        f.activate(other)
        assertEquals(AppResult.Success(null), f.outbox().observeVersion(f.access).first())
        assertEquals(AppResult.Success(null), f.outbox().observeVersion(other).first())
        f.activate(f.access)
        assertEquals(before, f.version())
    }

    private fun CollaborationSpaceHistoryFixture.outbox(
        store: ReplicaStore = database.replicas,
        cipher: ContentCipher = this.cipher,
    ) = EncryptedCollaborationSpaceRecipientOutboxRepository(store, cipher, keys)

    private fun CollaborationSpaceHistoryFixture.drafts(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceRecipientDraftRepository(store, cipher, keys)

    private suspend fun CollaborationSpaceHistoryFixture.marker() =
        database.replicas
            .observeDocument(
                spaceHistoryScope(access),
                recipientDraftPartition(access),
                RECIPIENT_OUTBOX_VERSION_ID,
            )
            .first()

    private suspend fun CollaborationSpaceHistoryFixture.version() =
        assertIs<AppResult.Success<CollaborationSpaceRecipientOutboxVersion?>>(
                outbox().observeVersion(access).first()
            )
            .value!!

    private fun CollaborationSpaceHistoryFixture.submission() =
        CollaborationSpaceRecipientSubmission(
            CollaborationSpaceRecipientDraft(
                historyId(9200),
                historyId(9300),
                "Original",
                Instant.fromEpochMilliseconds(1_793_491_200_000L),
            ),
            historyId(9301),
            Instant.fromEpochMilliseconds(1_793_491_200_000L),
        )

    private suspend fun CollaborationSpaceHistoryFixture.legacy():
        CollaborationSpaceRecipientSubmission {
        val sent = submission()
        CollaborationHistoryKeys(database.replicas, cipher, keys).use(create = true) { key, _ ->
            val generation = historyId(9600)
            val drafts = CollaborationSpaceRecipientDraftCodec(access, cipher, key)
            val originals = CollaborationSpaceRecipientSubmissionCodec(access, cipher, key)
            assertTrue(
                database.replicas.replace(
                    spaceHistoryScope(access),
                    recipientDraftPartition(access),
                    null,
                    ReplicaSnapshot(
                        generation,
                        drafts.checkpoint(generation, 0),
                        listOf(
                            drafts.encode(generation, 0, sent.nextDraft),
                            originals.encode(generation, 0, sent),
                        ),
                    ),
                )
            )
        }
        return sent
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
