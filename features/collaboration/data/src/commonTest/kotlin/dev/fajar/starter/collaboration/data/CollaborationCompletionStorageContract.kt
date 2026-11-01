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

/**
 * Real encrypted completion storage, never a substitute for retirement admission/history policy.
 */
abstract class CollaborationCompletionStorageContract {
    protected abstract fun databaseOwner(): CollaborationListDatabaseOwner

    @Test
    fun completionRetainsExactIdentityAndAliasesAcrossReopenAndLogin() = withDatabase { f ->
        val completion = f.sent()
        val previous = f.access
        assertEquals(AppResult.Success(true), f.repo.archiveCompletion(f.access, completion))
        assertTrue(f.rows().isEmpty())
        assertEquals(completion.record, f.read())
        assertEquals(AppResult.Success(completion), f.repo.readCompletion(f.access, f.intent.id))
        val raw =
            assertNotNull(
                f.database.commands.archived(
                    messageScope(f.access),
                    messagePartition(f.access),
                    f.intent.id,
                )
            )
        assertFalse(raw.record.request.contains(f.intent.text))
        assertFalse(raw.evidence.contains("primary"))
        assertFalse(raw.evidence.contains("alias"))
        f.reopen(f.access.copy(sessionId = "new-session"))
        assertEquals(completion.record, f.read())
        assertEquals(AppResult.Success(completion), f.repo.readCompletion(f.access, f.intent.id))
        assertEquals(AppResult.Success(null), f.repo.get(previous, f.intent.id))
        assertEquals(AppResult.Success(null), f.repo.readCompletion(previous, f.intent.id))
        assertTrue(f.rows().isEmpty())
    }

    @Test
    fun repeatedSubmissionCannotRequeueOrChangeAnArchivedMessage() = withDatabase { f ->
        val completion = f.sent()
        assertEquals(AppResult.Success(true), f.repo.archiveCompletion(f.access, completion))
        f.insert()
        assertIs<AppResult.Failed>(f.repo.insert(f.access, f.intent.copy(text = "replacement")))
        assertEquals(AppResult.Success(true), f.repo.archiveCompletion(f.access, completion))
        assertEquals(
            AppResult.Success(false),
            f.repo.archiveCompletion(
                f.access,
                completion.copy(eventIds = listOf("$" + "primary", "$" + "other")),
            ),
        )
        val sent = completion.record.state as CollaborationMessageState.Sent
        assertEquals(
            AppResult.Success(false),
            f.repo.archiveCompletion(
                f.access,
                completion.copy(
                    record = completion.record.copy(state = sent.copy(transactionId = "changed"))
                ),
            ),
        )
        assertTrue(f.rows().isEmpty())
        assertEquals(completion.record, f.read())
        assertEquals(AppResult.Success(completion), f.repo.readCompletion(f.access, f.intent.id))
    }

    @Test
    fun missingPendingAndLegacyStatesCannotInventCompletion() = withDatabase { f ->
        f.insert()
        val queued = f.read()
        assertIs<AppResult.Failed>(
            f.repo.archiveCompletion(
                f.access,
                CollaborationMessageCompletion(queued, listOf("$" + "primary")),
            )
        )
        assertEquals(
            AppResult.Success(true),
            f.repo.update(
                f.access,
                f.read(),
                CollaborationMessageState.Staging(f.device, emptySet()),
            ),
        )
        assertEquals(
            AppResult.Success(true),
            f.repo.update(
                f.access,
                f.read(),
                CollaborationMessageState.Staged(f.device, "transaction"),
            ),
        )
        assertIs<AppResult.Failed>(
            f.repo.archiveCompletion(
                f.access,
                CollaborationMessageCompletion(f.read(), listOf("$" + "primary")),
            )
        )
        assertEquals(
            AppResult.Success(true),
            f.repo.update(
                f.access,
                f.read(),
                CollaborationMessageState.Sent(f.device, "transaction", "$" + "primary"),
            ),
        )
        assertIs<AppResult.Failed>(
            f.repo.archiveCompletion(
                f.access,
                CollaborationMessageCompletion(f.read(), listOf("$" + "primary")),
            )
        )
        assertEquals(AppResult.Success(null), f.repo.readCompletion(f.access, f.intent.id))
        assertEquals(1, f.rows().size)
    }

    @Test
    fun proofCannotBeTransplantedOntoAnotherValidCiphertextOrRecord() = withDatabase { f ->
        val completion = f.sent()
        assertEquals(AppResult.Success(true), f.repo.archiveCompletion(f.access, completion))
        val store = f.database.commands
        val original =
            assertNotNull(
                store.archived(messageScope(f.access), messagePartition(f.access), f.intent.id)
            )
        val key = Base64.decode(assertNotNull(f.keys.read()))
        try {
            val codec = CollaborationMessageCodec(f.access, AesGcmContentCipher(), key)
            val reencodedState =
                codec.state(f.intent.id, original.record.revision, completion.record.state.toDto())
            val changedRequest = codec.request(f.intent.copy(text = "other valid text"))
            for (altered in
                listOf(
                    original.copy(record = original.record.copy(state = reencodedState)),
                    original.copy(record = original.record.copy(request = changedRequest)),
                    original.copy(record = original.record.copy(sortKey = "replaced")),
                    original.copy(evidence = Base64.encode(ByteArray(32))),
                )) {
                val substituted =
                    object : CommandStore by store {
                        override suspend fun archived(
                            scope: CommandScope,
                            partition: String,
                            id: String,
                        ): CommandArchive? = altered
                    }
                val repo = f.repository(store = substituted)
                assertIs<AppResult.Failed>(repo.get(f.access, f.intent.id))
                assertIs<AppResult.Failed>(repo.readCompletion(f.access, f.intent.id))
                assertIs<AppResult.Failed>(repo.archiveCompletion(f.access, completion))
            }
        } finally {
            key.fill(0)
        }
        assertEquals(AppResult.Success(completion), f.repo.readCompletion(f.access, f.intent.id))
        assertTrue(f.rows().isEmpty())
    }

    @Test
    fun completionCannotMoveAcrossAccountsOrRegenerateALostKey() = withDatabase { f ->
        val completion = f.sent()
        assertEquals(AppResult.Success(true), f.repo.archiveCompletion(f.access, completion))
        val original = f.access
        val other = original.copy(sessionId = "other-session", accountId = messageTestId(99))
        f.activate(other)
        assertEquals(AppResult.Success(null), f.repo.get(other, f.intent.id))
        assertEquals(AppResult.Success(null), f.repo.readCompletion(other, f.intent.id))
        assertIs<AppResult.Failed>(f.repo.archiveCompletion(other, completion))
        val key = f.keys.read()
        f.keys.write(null)
        assertIs<AppResult.Failed>(
            f.repo.insert(
                other,
                f.intent.copy(id = messageTestId(88), owner = other.messageOwner()),
            )
        )
        assertNull(f.keys.read())
        f.activate(original.copy(sessionId = "returned"))
        assertIs<AppResult.Failed>(f.repo.get(f.access, f.intent.id))
        assertIs<AppResult.Failed>(f.repo.readCompletion(f.access, f.intent.id))
        assertNull(f.keys.read())
        f.keys.write(key)
        assertEquals(AppResult.Success(completion), f.repo.readCompletion(f.access, f.intent.id))
    }

    @Test
    fun aliasesAreDetachedBeforeProofEncryptionSuspends() = withDatabase { f ->
        val completion = f.sent()
        val aliases = completion.eventIds.toMutableList()
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
            val request = async {
                f.repository(cipher = cipher)
                    .archiveCompletion(f.access, completion.copy(eventIds = aliases))
            }
            try {
                entered.await()
                aliases += "$" + "late"
            } finally {
                release.complete(Unit)
            }
            assertEquals(AppResult.Success(true), request.await())
        }
        assertEquals(AppResult.Success(completion), f.repo.readCompletion(f.access, f.intent.id))
    }

    @Test
    fun cancellationBeforeArchiveCommitPreservesThePendingCommand() = withDatabase { f ->
        val completion = f.sent()
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
            val request = launch {
                f.repository(cipher = cipher).archiveCompletion(f.access, completion)
            }
            entered.await()
            request.cancelAndJoin()
        }
        assertEquals(completion.record, f.read())
        assertEquals(1, f.rows().size)
        assertEquals(AppResult.Success(null), f.repo.readCompletion(f.access, f.intent.id))
        assertEquals(AppResult.Success(true), f.repo.archiveCompletion(f.access, completion))
    }

    @Test
    fun lateOwnerReplacementCannotCommitThePreparedProof() = withDatabase { f ->
        val completion = f.sent()
        val original = f.access
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
            val request = async {
                f.repository(cipher = cipher).archiveCompletion(original, completion)
            }
            try {
                entered.await()
                f.activate(original.copy(sessionId = "replacement", accountId = messageTestId(77)))
            } finally {
                release.complete(Unit)
            }
            assertEquals(AppResult.Success(false), request.await())
        }
        f.activate(original)
        assertEquals(completion.record, f.read())
        assertEquals(AppResult.Success(null), f.repo.readCompletion(f.access, f.intent.id))
    }

    @Test
    fun lostCommittedResponseIsRecoveredWithoutAnotherPendingInsertion() = withDatabase { f ->
        val completion = f.sent()
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
        assertIs<AppResult.Failed>(
            f.repository(store = lost).archiveCompletion(f.access, completion)
        )
        f.reopen(f.access.copy(sessionId = "recovery-session"))
        assertEquals(AppResult.Success(completion), f.repo.readCompletion(f.access, f.intent.id))
        assertEquals(AppResult.Success(true), f.repo.archiveCompletion(f.access, completion))
        f.insert()
        assertTrue(f.rows().isEmpty())
        assertEquals(completion.record, f.read())
    }

    @Test
    fun competingProofsCannotOverwriteTheFirstCompletion() = withDatabase { f ->
        val completion = f.sent()
        val different = completion.copy(eventIds = listOf("$" + "primary", "$" + "different"))
        coroutineScope {
            val choices =
                listOf(completion, different).map { item ->
                    async { f.repo.archiveCompletion(f.access, item) }
                }
            assertEquals(
                1,
                choices.count { assertIs<AppResult.Success<Boolean>>(it.await()).value },
            )
        }
        val retained =
            assertIs<AppResult.Success<CollaborationMessageCompletion?>>(
                    f.repo.readCompletion(f.access, f.intent.id)
                )
                .value
        assertTrue(retained in listOf(completion, different))
        assertEquals(completion.record, f.read())
        assertTrue(f.rows().isEmpty())
    }

    @Test
    fun insertionThatStartedBeforeAnotherWriterCompletedCannotResurrectTheId() = withDatabase { f ->
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
            val first = async { f.repository(store = held).insert(f.access, f.intent) }
            val completion =
                try {
                    entered.await()
                    f.sent().also {
                        assertEquals(
                            AppResult.Success(true),
                            f.repo.archiveCompletion(f.access, it),
                        )
                    }
                } finally {
                    release.complete(Unit)
                }
            // Independently encrypted immutable requests differ; a retry reads the retained proof.
            assertIs<AppResult.Failed>(first.await())
            f.insert()
            assertEquals(completion.record, f.read())
            assertEquals(
                AppResult.Success(completion),
                f.repo.readCompletion(f.access, f.intent.id),
            )
            assertTrue(f.rows().isEmpty())
        }
    }

    @Test
    fun invalidAliasesAndChangedExpectedRecordCannotRemovePendingWork() = withDatabase { f ->
        val completion = f.sent()
        for (aliases in
            listOf(
                emptyList(),
                listOf("$" + "alias"),
                listOf("$" + "primary", "$" + "primary"),
                listOf("invalid"),
            )) {
            assertIs<AppResult.Failed>(
                f.repo.archiveCompletion(f.access, completion.copy(eventIds = aliases))
            )
        }
        assertEquals(
            AppResult.Success(false),
            f.repo.archiveCompletion(
                f.access,
                completion.copy(
                    record = completion.record.copy(revision = completion.record.revision + 1)
                ),
            ),
        )
        assertEquals(
            AppResult.Success(false),
            f.repo.archiveCompletion(
                f.access,
                completion.copy(
                    record = completion.record.copy(intent = f.intent.copy(text = "changed"))
                ),
            ),
        )
        assertEquals(completion.record, f.read())
        assertEquals(1, f.rows().size)
        assertEquals(AppResult.Success(null), f.repo.readCompletion(f.access, f.intent.id))
    }

    @Test
    fun oversizedProofNeverTruncatesAliasesOrRemovesTheCommand() = withDatabase { f ->
        val completion = f.sent()
        val oversized =
            completion.copy(
                eventIds =
                    listOf("$" + "primary") +
                        (1..127).map { "$" + "\"".repeat(250) + it.toString().padStart(4, '0') }
            )
        assertIs<AppResult.Failed>(f.repo.archiveCompletion(f.access, oversized))
        assertEquals(completion.record, f.read())
        assertEquals(1, f.rows().size)
        assertEquals(AppResult.Success(null), f.repo.readCompletion(f.access, f.intent.id))
        val maximum =
            completion.copy(
                eventIds =
                    listOf("$" + "primary") +
                        (1..127).map { "$" + "a".repeat(250) + it.toString().padStart(4, '0') }
            )
        assertEquals(AppResult.Success(true), f.repo.archiveCompletion(f.access, maximum))
        assertEquals(AppResult.Success(maximum), f.repo.readCompletion(f.access, f.intent.id))
    }

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

private suspend fun MessageStorageFixture.sent(): CollaborationMessageCompletion {
    insert()
    val origin = collaborationTextOrigin("@fictional:example.test", intent.id, intent.text)
    assertEquals(
        AppResult.Success(true),
        repo.update(access, read(), CollaborationMessageState.Staging(device, emptySet(), origin)),
    )
    assertEquals(
        AppResult.Success(true),
        repo.update(access, read(), CollaborationMessageState.Staged(device, "transaction", origin)),
    )
    assertEquals(
        AppResult.Success(true),
        repo.update(
            access,
            read(),
            CollaborationMessageState.Sent(device, "transaction", "$" + "primary", origin),
        ),
    )
    return CollaborationMessageCompletion(read(), listOf("$" + "primary", "$" + "alias"))
}
