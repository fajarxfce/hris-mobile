package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationMessageRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** Real SQLite/IndexedDB contract, including original-transaction recovery across login changes. */
abstract class CollaborationMessageStorageContract {
    protected abstract fun databaseOwner(): CollaborationListDatabaseOwner

    @Test
    fun maximumQueueSnapshotFitsTheAuthenticatedStateEnvelope() = withDatabase { f ->
        f.insert()
        val before = (1..128).map { it.toString().padStart(128, 'a') }.toSet()
        val staged = CollaborationMessageState.Staging(f.device, before)
        assertEquals(AppResult.Success(true), f.repo.update(f.access, f.read(), staged))
        f.reopen(f.access)
        assertEquals(staged, f.read().state)
        val raw =
            requireNotNull(
                f.database.commands.get(
                    messageScope(f.access),
                    messagePartition(f.access),
                    f.intent.id,
                )
            )
        assertTrue(raw.state.length <= 32 * 1024)
    }

    @Test
    fun uncertainIntentAndOriginalTransactionSurviveReopenAndReauthentication() =
        withDatabase { f ->
            f.insert()
            val original = f.read()
            val pending = CollaborationMessageState.Staging(f.device, setOf("older-transaction"))
            assertEquals(AppResult.Success(true), f.repo.update(f.access, original, pending))
            f.reopen(f.access.copy(sessionId = "renewed-login"))
            assertEquals(pending, f.read().state)
            val transaction = CollaborationMessageState.Staged(f.device, "original-transaction")
            assertEquals(AppResult.Success(true), f.repo.update(f.access, f.read(), transaction))
            f.reopen(f.access.copy(sessionId = "another-login"))
            val restored = f.read()
            assertEquals(f.intent, restored.intent)
            assertEquals(transaction, restored.state)
            assertEquals(2, restored.revision)
            assertEquals(
                AppResult.Success(true),
                f.repo.update(
                    f.access,
                    restored,
                    CollaborationMessageState.Sent(f.device, "original-transaction", "\$event"),
                ),
            )
            assertEquals(listOf(f.read()), f.rows())
        }

    @Test
    fun idempotentInsertionCannotChangeContentOrDiscardAnUncertainAttempt() = withDatabase { f ->
        f.insert()
        assertEquals(
            AppResult.Success(true),
            f.repo.update(
                f.access,
                f.read(),
                CollaborationMessageState.Staging(f.device, emptySet()),
            ),
        )
        val pending = f.read()
        f.insert()
        assertEquals(pending, f.read())
        for (different in
            listOf(
                f.intent.copy(text = "Different message"),
                f.intent.copy(conversationId = messageTestId(12)),
                f.intent.copy(createdAt = Instant.fromEpochMilliseconds(1)),
            )) assertIs<AppResult.Failed>(f.repo.insert(f.access, different))
        assertEquals(pending, f.read())
    }

    @Test
    fun competingRevisionsCannotReplaceFirstPublishedMappingOrResetIt() = withDatabase { f ->
        f.insert()
        val queued = f.read()
        val attempt = CollaborationMessageState.Staging(f.device, emptySet())
        assertEquals(AppResult.Success(true), f.repo.update(f.access, queued, attempt))
        assertEquals(AppResult.Success(false), f.repo.update(f.access, queued, attempt))
        val staged = f.read()
        assertEquals(
            AppResult.Success(true),
            f.repo.update(f.access, staged, CollaborationMessageState.Staged(f.device, "original")),
        )
        assertEquals(
            AppResult.Success(false),
            f.repo.update(
                f.access,
                staged,
                CollaborationMessageState.Staged(f.device, "replacement"),
            ),
        )
        assertIs<AppResult.Failed>(
            f.repo.update(f.access, f.read(), CollaborationMessageState.Queued)
        )
        assertIs<AppResult.Failed>(
            f.repo.update(
                f.access,
                f.read(),
                CollaborationMessageState.Sent(f.device, "replacement", "\$event"),
            )
        )
    }

    @Test
    fun inactiveForeignAndReplacedAccountsCannotReadOrMutateRetainedMessages() = withDatabase { f ->
        f.insert()
        val retained = f.read()
        val original = f.access
        f.activate(original.copy(accountId = messageTestId(99)))
        assertTrue(f.rows().isEmpty())
        assertEquals(AppResult.Success(null), f.repo.get(original, f.intent.id))
        assertEquals(
            AppResult.Success(false),
            f.repo.update(
                original,
                retained,
                CollaborationMessageState.Staging(f.device, emptySet()),
            ),
        )
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.SCOPE_CHANGED),
            f.repo.insert(original, f.intent.copy(id = messageTestId(90))),
        )
        f.activate(original)
        assertEquals(retained, f.read())
        assertEquals(
            AppResult.Success(emptyList()),
            f.repo.observe(original.copy(companyId = messageTestId(88)), null).first(),
        )
    }

    @Test
    fun encryptedPayloadAndIndexAuthenticationRejectTamperingWithoutKeyReplacement() =
        withDatabase { f ->
            f.insert()
            val raw =
                requireNotNull(
                    f.database.commands.get(
                        messageScope(f.access),
                        messagePartition(f.access),
                        f.intent.id,
                    )
                )
            assertFalse(raw.request.contains("Private fictional text"))
            assertFalse(raw.request.contains(f.intent.conversationId))
            val fake =
                object : CommandStore by f.database.commands {
                    override suspend fun get(scope: CommandScope, partition: String, id: String) =
                        raw.copy(sortKey = "wrong")
                }
            assertIs<AppResult.Failed>(f.repository(fake).get(f.access, f.intent.id))
            f.keys.write(null)
            assertIs<AppResult.Failed>(f.repo.get(f.access, f.intent.id))
            assertIs<AppResult.Failed>(
                f.repo.insert(f.access, f.intent.copy(id = messageTestId(91)))
            )
            assertNull(f.keys.read())
            f.keys.write("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            assertIs<AppResult.Failed>(f.repo.get(f.access, f.intent.id))
        }

    @Test
    fun accountCapacityIsFiniteAndDistinctMessagesInOneConversationRemainIndependent() =
        withDatabase { f ->
            for (index in 1..128) f.insert(f.intent.copy(id = messageTestId(1000 + index)))
            val rows = f.rows()
            assertEquals(128, rows.size)
            assertEquals((1..128).map { messageTestId(1000 + it) }, rows.map { it.intent.id })
            assertEquals(
                AppResult.Success(CollaborationMessageInsertion.CAPACITY_EXCEEDED),
                f.repo.insert(f.access, f.intent),
            )
            assertEquals(128, f.rows().size)
            assertEquals(
                AppResult.Success(emptyList()),
                f.repo.observe(f.access, messageTestId(555)).first(),
            )
        }

    @Test
    fun cancellationDuringEncryptionLeavesTheQueuedRecordAndOriginalRevisionIntact() =
        withDatabase { f ->
            f.insert()
            val original = f.read()
            val entered = CompletableDeferred<Unit>()
            val suspended =
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
                val task = launch {
                    f.repository(cipher = suspended)
                        .update(
                            f.access,
                            original,
                            CollaborationMessageState.Staging(f.device, emptySet()),
                        )
                }
                entered.await()
                task.cancelAndJoin()
            }
            assertEquals(original, f.read())
        }

    @Test
    fun publishedStateDetachesMutableTransactionSnapshotBeforeStorageSuspends() =
        withDatabase { f ->
            f.insert()
            val before = mutableSetOf("old")
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun get(
                        scope: CommandScope,
                        partition: String,
                        id: String,
                    ): CommandRecord? {
                        before.add("late")
                        return f.database.commands.get(scope, partition, id)
                    }
                }
            assertEquals(
                AppResult.Success(true),
                f.repository(store)
                    .update(f.access, f.read(), CollaborationMessageState.Staging(f.device, before)),
            )
            assertEquals(
                setOf("old"),
                assertIs<CollaborationMessageState.Staging>(f.read().state).before,
            )
        }

    private fun withDatabase(block: suspend (MessageStorageFixture) -> Unit) = runTest {
        val owner = databaseOwner()
        val fixture = MessageStorageFixture(owner)
        try {
            fixture.activate(fixture.access)
            block(fixture)
        } finally {
            fixture.database.close()
            owner.dispose()
        }
    }
}

private class MessageStorageFixture(private val owner: CollaborationListDatabaseOwner) {
    var database = owner.open()
    val keys = MemoryCredentialStore()
    var access =
        CollaborationLocalAccess(
            "local-session",
            "https://hris.example.test/",
            messageTestId(1),
            messageTestId(2),
        )
    val intent =
        CollaborationMessageIntent(
            messageTestId(3),
            access.messageOwner(),
            messageTestId(4),
            Instant.parse("2026-11-01T00:00:00Z"),
            "Private fictional text\n  with whitespace 🙂",
        )
    val device =
        CollaborationMessageDevice(
            messageTestId(5),
            messageTestId(6),
            messageTestId(7),
            "!room:example.test",
        )
    var repo = repository()

    fun repository(
        store: CommandStore = database.commands,
        cipher: ContentCipher = AesGcmContentCipher(),
    ) = EncryptedCollaborationMessageRepository(store, cipher, keys)

    suspend fun activate(next: CollaborationLocalAccess) {
        access = next
        database.accounts.activate(next.sessionId, messageScope(next).accountKey)
    }

    suspend fun reopen(next: CollaborationLocalAccess) {
        database.close()
        database = owner.open()
        activate(next)
        repo = repository()
    }

    suspend fun insert(value: CollaborationMessageIntent = intent) {
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            repo.insert(access, value),
        )
    }

    suspend fun read() =
        requireNotNull(
            assertIs<AppResult.Success<CollaborationMessageRecord?>>(repo.get(access, intent.id))
                .value
        )

    suspend fun rows() =
        assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                repo.observe(access, null).first()
            )
            .value
}

internal fun messageTestId(value: Int) =
    "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
