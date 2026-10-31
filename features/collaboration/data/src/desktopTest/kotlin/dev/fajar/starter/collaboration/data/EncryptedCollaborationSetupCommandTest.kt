package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSetupCommandRepository
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

class EncryptedCollaborationSetupCommandTest {
    private val owner =
        CollaborationOwner(
            "local",
            "https://hris.example.test/",
            "10000000-0000-4000-8000-000000000001",
            "20000000-0000-4000-8000-000000000001",
            "30000000-0000-4000-8000-000000000001",
        )
    private val device = "40000000-0000-4000-8000-000000000001"
    private val command =
        CollaborationSetupCommand(
            "50000000-0000-4000-8000-000000000001",
            owner.setupBinding(),
            CollaborationNativePlatform.ANDROID,
            5,
            Instant.parse("2026-10-01T00:00:00Z"),
            CollaborationSetupAction.Register("Fictional employee phone"),
        )

    @Test
    fun immutableIntentAndOutcomeSurviveDatabaseReopenAndLocalSessionReplacement() = runTest {
        val directory = Files.createTempDirectory("collaboration-setup").toFile()
        val keys = MemoryCredentialStore()
        var database = createAppDatabase(directory)
        try {
            database.accounts.activate(
                owner.sessionId,
                accountStorageKey(owner.audience, owner.accountId),
            )
            var repo =
                EncryptedCollaborationSetupCommandRepository(
                    database.commands,
                    AesGcmContentCipher(),
                    keys,
                )
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                repo.insert(owner, command),
            )
            val original = read(repo)
            val receipt = CollaborationSetupOutcome.Registered(CollaborationSetupReceipt(device, 0))
            assertEquals(AppResult.Success(true), repo.update(owner, original, receipt, 1))
            val raw = raw(database)
            assertFalse(raw.request.contains("Fictional"))
            assertFalse(raw.request.contains("REGISTER"))
            assertFalse(raw.state.contains(device))
            database.close()
            database = createAppDatabase(directory)
            database.accounts.activate(null)
            repo =
                EncryptedCollaborationSetupCommandRepository(
                    database.commands,
                    AesGcmContentCipher(),
                    keys,
                )
            assertEquals(AppResult.Success(null), repo.current(owner))
            val replacement = owner.copy(sessionId = "restored-local")
            database.accounts.activate(
                replacement.sessionId,
                accountStorageKey(replacement.audience, replacement.accountId),
            )
            val restored = read(repo, replacement)
            assertEquals(command, restored.command)
            assertEquals(receipt, restored.outcome)
            assertEquals(1, restored.revision)
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                repo.insert(replacement, command),
            )
            assertEquals(restored, read(repo, replacement))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun bootstrapCredentialsRemainEncryptedAndReadableAfterTheirOrdinaryExpiry() = runTest {
        withDatabase { database, repo, _ ->
            val bootstrap =
                command.copy(action = CollaborationSetupAction.Bootstrap(device, 1, null))
            repo.insert(owner, bootstrap)
            val original = read(repo)
            val gateway = gateway()
            assertEquals(
                AppResult.Success(true),
                repo.update(owner, original, CollaborationSetupOutcome.Bootstrapped(gateway), 1),
            )
            val raw = raw(database)
            assertFalse(raw.state.contains(gateway.accessToken))
            assertFalse(raw.state.contains(gateway.refreshToken))
            val replacement = owner.copy(sessionId = "restored")
            database.accounts.activate(
                replacement.sessionId,
                accountStorageKey(owner.audience, owner.accountId),
            )
            val restored =
                EncryptedCollaborationSetupCommandRepository(
                    database.commands,
                    AesGcmContentCipher(),
                    credentials = MemoryCredentialStore(),
                )
            // An unrelated empty key store cannot open or replace the retained credentials.
            assertIs<AppResult.Failed>(restored.current(replacement))
            val retained = read(repo, replacement)
            val result = assertIs<CollaborationSetupOutcome.Bootstrapped>(retained.outcome).gateway
            assertEquals(replacement, result.owner)
            assertEquals(gateway.accessToken, result.accessToken)
            assertEquals(gateway.refreshToken, result.refreshToken)
            assertEquals(gateway.expiresAtEpochMillis, result.expiresAtEpochMillis)
            assertFalse(result.toString().contains(gateway.accessToken))
            assertFalse(retained.toString().contains(gateway.refreshToken))
        }
    }

    @Test
    fun foreignAccountsCompaniesAndNativeFamiliesCannotReadOrAlterTheIntent() = runTest {
        withDatabase { database, repo, _ ->
            repo.insert(owner, command)
            val original = read(repo)
            listOf(
                    owner.copy(companyId = "20000000-0000-4000-8000-000000000002"),
                    owner.copy(nativeSessionId = "30000000-0000-4000-8000-000000000002"),
                )
                .forEach { other ->
                    assertEquals(AppResult.Success(null), repo.current(other))
                    assertIs<AppResult.Failed>(repo.remove(other, original))
                    assertIs<AppResult.Failed>(repo.insert(other, command))
                }
            val other =
                owner.copy(accountId = "10000000-0000-4000-8000-000000000002", sessionId = "other")
            database.accounts.activate(
                other.sessionId,
                accountStorageKey(other.audience, other.accountId),
            )
            assertEquals(AppResult.Success(null), repo.current(other))
            assertEquals(AppResult.Success(false), repo.remove(owner, original))
            database.accounts.activate(
                owner.sessionId,
                accountStorageKey(owner.audience, owner.accountId),
            )
            assertEquals(original, read(repo))
        }
    }

    @Test
    fun missingOrWrongKeysPreserveTheOriginalCiphertext() = runTest {
        withDatabase { database, repo, keys ->
            repo.insert(owner, command)
            val original = raw(database)
            val key = keys.read()
            keys.write(null)
            assertIs<AppResult.Failed>(repo.current(owner))
            assertIs<AppResult.Failed>(
                repo.insert(owner, command.copy(id = "50000000-0000-4000-8000-000000000002"))
            )
            assertNull(keys.read())
            keys.write(kotlin.io.encoding.Base64.encode(ByteArray(32) { 7 }))
            assertIs<AppResult.Failed>(repo.current(owner))
            assertEquals(original, raw(database))
            keys.write(key)
            assertEquals(command, read(repo).command)
        }
    }

    @Test
    fun copiedCiphertextAndChangedRevisionCannotAuthenticate() = runTest {
        withDatabase { database, repo, _ ->
            repo.insert(owner, command)
            val original = raw(database)
            val other = owner.copy(nativeSessionId = "30000000-0000-4000-8000-000000000002")
            database.commands.insert(
                collaborationCommandScope(other),
                original.copy(partition = collaborationCommandPartition(other)),
            )
            assertIs<AppResult.Failed>(repo.current(other))
            assertEquals(command, read(repo).command)
            assertTrue(
                database.commands.update(
                    collaborationCommandScope(owner),
                    original.partition,
                    original.id,
                    original.revision,
                    original.state,
                )
            )
            assertIs<AppResult.Failed>(repo.current(owner))
            assertTrue(database.commands.containsRecords())
        }
    }

    @Test
    fun competingIntentsAndOutcomesKeepOneRequestAndOneRevisionWinner() = runTest {
        withDatabase { _, repo, _ ->
            val competing = command.copy(id = "50000000-0000-4000-8000-000000000002")
            val insertions =
                listOf(
                        async { repo.insert(owner, command) },
                        async { repo.insert(owner, competing) },
                    )
                    .awaitAll()
            assertEquals(
                1,
                insertions.count { it == AppResult.Success(CollaborationSetupInsertion.STORED) },
            )
            assertEquals(
                1,
                insertions.count { it == AppResult.Success(CollaborationSetupInsertion.OCCUPIED) },
            )
            val original = read(repo)
            val updates =
                listOf(
                        async {
                            repo.update(owner, original, CollaborationSetupOutcome.Unconfirmed(), 1)
                        },
                        async {
                            repo.update(owner, original, CollaborationSetupOutcome.Unconfirmed(), 1)
                        },
                    )
                    .awaitAll()
            assertEquals(1, updates.count { it == AppResult.Success(true) })
            assertEquals(1, updates.count { it == AppResult.Success(false) })
            assertEquals(1, read(repo).revision)
            assertIs<AppResult.Failed>(
                repo.insert(
                    owner,
                    original.command.copy(action = CollaborationSetupAction.Register("Changed")),
                )
            )
            assertEquals(AppResult.Success(false), repo.remove(owner, original))
        }
    }

    @Test
    fun cancellationBeforeCommitLeavesThePreviousDurableOutcome() = runTest {
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
                EncryptedCollaborationSetupCommandRepository(database.commands, cipher, keys)
            val pending = async {
                delayed.update(owner, original, CollaborationSetupOutcome.Unconfirmed(), 1)
            }
            entered.await()
            pending.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(original, read(repo))
        }
    }

    @Test
    fun mismatchedReceiptsCredentialsAndMalformedFailureMetadataCannotReplaceTheRecord() = runTest {
        withDatabase { _, repo, _ ->
            repo.insert(owner, command)
            val original = read(repo)
            listOf(
                    CollaborationSetupOutcome.Bootstrapped(gateway()),
                    CollaborationSetupOutcome.Retried(CollaborationSetupReceipt(device, 0)),
                    CollaborationSetupOutcome.Unconfirmed(
                        Failure(FailureKind.Network, "Private body", code = "invalid code")
                    ),
                )
                .forEach { assertIs<AppResult.Failed>(repo.update(owner, original, it, 1)) }
            assertIs<AppResult.Failed>(
                repo.update(owner, original, CollaborationSetupOutcome.Unconfirmed(), 1_001)
            )
            assertEquals(original, read(repo))
        }
    }

    private fun gateway() =
        CollaborationGateway(
            owner,
            device,
            "60000000-0000-4000-8000-000000000001",
            0,
            "@hris_${owner.companyId.replace("-", "")}_${owner.accountId.replace("-", "")}:matrix.example.test",
            "HRIS_${device.replace("-", "")}",
            "hris_mx_a_" + "a".repeat(43),
            "hris_mx_r_" + "b".repeat(43),
            Instant.parse("2026-10-01T00:05:00Z").toEpochMilliseconds(),
            Instant.parse("2026-10-02T00:00:00Z").toEpochMilliseconds(),
        )

    private suspend fun read(
        repo: EncryptedCollaborationSetupCommandRepository,
        access: CollaborationOwner = owner,
    ) = assertIs<AppResult.Success<CollaborationSetupCommandRecord?>>(repo.current(access)).value!!

    private suspend fun raw(database: AppDatabase) =
        database.commands.get(
            collaborationCommandScope(owner),
            collaborationCommandPartition(owner),
            command.id,
        )!!

    private suspend fun withDatabase(
        block:
            suspend (
                AppDatabase, EncryptedCollaborationSetupCommandRepository, MemoryCredentialStore,
            ) -> Unit
    ) {
        val directory = Files.createTempDirectory("collaboration-setup").toFile()
        val database = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            database.accounts.activate(
                owner.sessionId,
                accountStorageKey(owner.audience, owner.accountId),
            )
            block(
                database,
                EncryptedCollaborationSetupCommandRepository(
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
