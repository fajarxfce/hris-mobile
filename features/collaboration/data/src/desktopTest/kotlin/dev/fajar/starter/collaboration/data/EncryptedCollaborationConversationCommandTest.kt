package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationConversationCommandRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.*
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class EncryptedCollaborationConversationCommandTest {
    private val owner = setupOwner
    private val command =
        CollaborationConversationCommand(
            SETUP_OPERATION,
            owner.setupBinding(),
            Instant.parse("2026-10-01T00:00:00Z"),
            CollaborationConversationAction.Open(PEER_ID),
        )

    @Test
    fun commandAndReceiptSurviveDatabaseReopenAndLocalSessionReplacement() = runTest {
        val directory = Files.createTempDirectory("conversation-command").toFile()
        val keys = MemoryCredentialStore()
        var database = createAppDatabase(directory)
        try {
            database.accounts.activate(
                owner.sessionId,
                accountStorageKey(owner.audience, owner.accountId),
            )
            var repo =
                EncryptedCollaborationConversationCommandRepository(
                    database.commands,
                    AesGcmContentCipher(),
                    keys,
                )
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                repo.insert(owner, command),
            )
            val confirmed =
                CollaborationConversationOutcome.Confirmed(
                    CollaborationSetupReceipt(CONVERSATION_ID, 0)
                )
            assertEquals(AppResult.Success(true), repo.update(owner, read(repo), confirmed, 1))
            val raw = raw(database)
            assertFalse(raw.request.contains(PEER_ID))
            assertFalse(raw.state.contains(CONVERSATION_ID))
            database.close()
            database = createAppDatabase(directory)
            database.accounts.activate(null)
            repo =
                EncryptedCollaborationConversationCommandRepository(
                    database.commands,
                    AesGcmContentCipher(),
                    keys,
                )
            assertEquals(AppResult.Success(null), repo.get(owner, command.id))
            val renewed = owner.copy(sessionId = "restored-local")
            database.accounts.activate(
                renewed.sessionId,
                accountStorageKey(owner.audience, owner.accountId),
            )
            val restored = read(repo, renewed)
            assertEquals(command, restored.command)
            assertEquals(confirmed, restored.outcome)
            assertEquals(1, restored.revision)
            assertEquals(
                AppResult.Success(restored),
                repo.current(renewed, command.action.resourceId()),
            )
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                repo.insert(renewed, command),
            )
            assertEquals(restored, read(repo, renewed))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun peerAndRoomResourcesAreIndependentButCompetingIntentCannotReplaceSameResource() = runTest {
        withDatabase { _, repo, _ ->
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                repo.insert(owner, command),
            )
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.OCCUPIED),
                repo.insert(owner, command.copy(id = SETUP_JOB)),
            )
            val room =
                command.copy(
                    id = SETUP_JOB,
                    action =
                        CollaborationConversationAction.RequestRoom(
                            CONVERSATION_ID,
                            SETUP_DEVICE,
                            1,
                            null,
                        ),
                )
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                repo.insert(owner, room),
            )
            assertNotNull(
                assertIs<AppResult.Success<CollaborationConversationCommandRecord?>>(
                        repo.current(owner, room.action.resourceId())
                    )
                    .value
            )
            assertEquals(command, read(repo).command)
        }
    }

    @Test
    fun accountCompanyAndNativeFamilyCannotReadOrRewriteAnotherPartition() = runTest {
        withDatabase { database, repo, _ ->
            repo.insert(owner, command)
            val expected = read(repo)
            for (other in
                listOf(
                    owner.copy(companyId = SETUP_JOB),
                    owner.copy(nativeSessionId = SETUP_JOB),
                )) {
                assertEquals(AppResult.Success(null), repo.get(other, command.id))
                assertIs<AppResult.Failed>(repo.remove(other, expected))
                assertIs<AppResult.Failed>(repo.insert(other, command))
            }
            database.accounts.activate("other", accountStorageKey(owner.audience, PEER_ID))
            assertEquals(AppResult.Success(false), repo.remove(owner, expected))
            database.accounts.activate(
                owner.sessionId,
                accountStorageKey(owner.audience, owner.accountId),
            )
            assertEquals(expected, read(repo))
        }
    }

    @Test
    fun missingAndWrongKeysPreserveOriginalCiphertextAndCannotGenerateOverOwnedRecords() = runTest {
        withDatabase { database, repo, keys ->
            repo.insert(owner, command)
            val original = raw(database)
            val key = keys.read()
            keys.write(null)
            assertIs<AppResult.Failed>(repo.get(owner, command.id))
            assertIs<AppResult.Failed>(
                repo.insert(
                    owner,
                    command.copy(
                        id = SETUP_JOB,
                        action = CollaborationConversationAction.Open(SETUP_DEVICE),
                    ),
                )
            )
            assertNull(keys.read())
            assertEquals(original, raw(database))
            keys.write("invalid-key")
            assertIs<AppResult.Failed>(repo.get(owner, command.id))
            assertEquals(original, raw(database))
            keys.write(key)
            assertEquals(command, read(repo).command)
        }
    }

    @Test
    fun concurrentOutcomeWritesUseObservedRevisionAndExactRecord() = runTest {
        withDatabase { _, repo, _ ->
            repo.insert(owner, command)
            val original = read(repo)
            assertEquals(
                AppResult.Success(false),
                repo.update(
                    owner,
                    original.copy(attempts = 9),
                    CollaborationConversationOutcome.Unconfirmed(),
                    1,
                ),
            )
            val results =
                List(2) {
                        async {
                            repo.update(
                                owner,
                                original,
                                CollaborationConversationOutcome.Unconfirmed(),
                                1,
                            )
                        }
                    }
                    .awaitAll()
            assertEquals(1, results.count { it == AppResult.Success(true) })
            assertEquals(1, results.count { it == AppResult.Success(false) })
            assertEquals(AppResult.Success(false), repo.remove(owner, original))
            assertEquals(1, read(repo).revision)
        }
    }

    @Test
    fun movedCiphertextCannotAcquireAnotherResourceIdentity() = runTest {
        withDatabase { database, repo, _ ->
            repo.insert(owner, command)
            val original = raw(database)
            val resource = CollaborationConversationAction.Open(SETUP_DEVICE).resourceId()
            assertEquals(
                CommandInsertResult.Stored,
                database.commands.insert(
                    collaborationCommandScope(owner),
                    original.copy(
                        id = SETUP_JOB,
                        resourceId = resource,
                        sortKey = original.sortKey.substringBefore('/') + "/" + SETUP_JOB,
                    ),
                ),
            )
            assertIs<AppResult.Failed>(repo.current(owner, resource))
            assertEquals(command, read(repo).command)
        }
    }

    @Test
    fun cancelledEncryptionCannotPublishAnUnobservedOutcome() = runTest {
        withDatabase { database, repo, keys ->
            repo.insert(owner, command)
            val original = read(repo)
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
                        withContext(NonCancellable) { release.await() }
                        return AesGcmContentCipher().encrypt(key, associatedData, plaintext)
                    }
                }
            val delayed =
                EncryptedCollaborationConversationCommandRepository(database.commands, cipher, keys)
            val updating = async {
                delayed.update(owner, original, CollaborationConversationOutcome.Unconfirmed(), 1)
            }
            entered.await()
            updating.cancel()
            release.complete(Unit)
            updating.join()
            assertTrue(updating.isCancelled)
            assertEquals(original, read(repo))
        }
    }

    @Test
    fun malformedReceiptOrFailureAndRewrittenCommandCannotReplaceStoredIntent() = runTest {
        withDatabase { _, repo, _ ->
            repo.insert(owner, command)
            val original = read(repo)
            assertIs<AppResult.Failed>(
                repo.update(
                    owner,
                    original,
                    CollaborationConversationOutcome.Confirmed(
                        CollaborationSetupReceipt(CONVERSATION_ID, 1)
                    ),
                    1,
                )
            )
            assertIs<AppResult.Failed>(
                repo.update(
                    owner,
                    original,
                    CollaborationConversationOutcome.Unconfirmed(
                        Failure(FailureKind.Network, "Private payload", code = "invalid code")
                    ),
                    1,
                )
            )
            assertIs<AppResult.Failed>(
                repo.update(owner, original, CollaborationConversationOutcome.Unconfirmed(), 1001)
            )
            assertIs<AppResult.Failed>(
                repo.insert(
                    owner,
                    command.copy(action = CollaborationConversationAction.Open(SETUP_DEVICE)),
                )
            )
            assertEquals(original, read(repo))
        }
    }

    private suspend fun read(
        repo: EncryptedCollaborationConversationCommandRepository,
        access: CollaborationOwner = owner,
    ) =
        assertIs<AppResult.Success<CollaborationConversationCommandRecord?>>(
                repo.get(access, command.id)
            )
            .value!!

    private suspend fun raw(database: AppDatabase) =
        requireNotNull(
            database.commands.get(
                collaborationCommandScope(owner),
                conversationCommandPartition(owner),
                command.id,
            )
        )

    private suspend fun withDatabase(
        block:
            suspend (
                AppDatabase,
                EncryptedCollaborationConversationCommandRepository,
                MemoryCredentialStore,
            ) -> Unit
    ) {
        val directory = Files.createTempDirectory("conversation-command").toFile()
        val database = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            database.accounts.activate(
                owner.sessionId,
                accountStorageKey(owner.audience, owner.accountId),
            )
            block(
                database,
                EncryptedCollaborationConversationCommandRepository(
                    database.commands,
                    AesGcmContentCipher(),
                    keys,
                ),
                keys,
            )
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
