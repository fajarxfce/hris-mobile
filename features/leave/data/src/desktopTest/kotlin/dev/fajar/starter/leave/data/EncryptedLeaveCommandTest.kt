package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.EncryptedLeaveCommandRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.securestorage.*
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class EncryptedLeaveCommandTest {
    private val now = Instant.parse("2026-10-11T00:00:00Z")
    private val access =
        LeaveAccess(
            "session",
            "10000000-0000-4000-8000-000000000001",
            "https://hris.example.test/",
            "20000000-0000-4000-8000-000000000001",
            setOf("leave.self.manage"),
            now + 24.hours,
            now + 1.hours,
            "client.workspace_expired",
        )
    private val command =
        LeaveCommand(
            "30000000-0000-4000-8000-000000000001",
            access.accountId,
            access.audience,
            access.companyId,
            "40000000-0000-4000-8000-000000000001",
            1,
            LeaveRequestAction.WITHDRAW,
            "Family appointment",
            now,
        )

    @Test
    fun encryptedRequestsAndOutcomesSurviveReopeningAndAnotherSessionForTheSameAccount() = runTest {
        val directory = Files.createTempDirectory("leave-command-reopen").toFile()
        var database = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            database.accounts.activate(
                access.sessionId,
                accountStorageKey(access.audience, access.accountId),
            )
            var repository =
                EncryptedLeaveCommandRepository(database.commands, AesGcmContentCipher(), keys)
            assertEquals(
                AppResult.Success(LeaveCommandAdmission.STORED),
                repository.insert(access, command),
            )
            val original =
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            assertEquals(
                AppResult.Success(true),
                repository.update(access, original, LeaveCommandOutcome.Unconfirmed(), 1),
            )
            val sending =
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            assertEquals(
                AppResult.Success(true),
                repository.update(access, sending, LeaveCommandOutcome.Confirmed(2)),
            )
            val confirmed =
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            val raw =
                database.commands.get(
                    leaveCommandScope(access),
                    leaveCommandPartition(access),
                    command.id,
                )!!
            assertFalse(raw.request.contains("Family appointment"))
            assertFalse(raw.state.contains("CONFIRMED"))
            assertEquals(2, raw.revision)
            database.close()
            database = createAppDatabase(directory)
            database.accounts.activate(null)
            assertNull(
                database.commands.get(
                    leaveCommandScope(access),
                    leaveCommandPartition(access),
                    command.id,
                )
            )
            val replacement = access.copy(sessionId = "replacement-session")
            database.accounts.activate(
                replacement.sessionId,
                accountStorageKey(replacement.audience, replacement.accountId),
            )
            repository =
                EncryptedLeaveCommandRepository(database.commands, AesGcmContentCipher(), keys)
            assertEquals(
                confirmed,
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(replacement, command.id)
                    )
                    .value,
            )
            assertEquals(
                AppResult.Success(LeaveCommandAdmission.STORED),
                repository.insert(replacement, command),
            )
            assertEquals(
                confirmed,
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(replacement, command.id)
                    )
                    .value,
            )
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun accountAndCompanyChangesCannotReadOrAlterTheRetainedOperation() = runTest {
        withDatabase { database, repository, _ ->
            repository.insert(access, command)
            val original =
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            val other =
                access.copy(
                    sessionId = "other-session",
                    accountId = "10000000-0000-4000-8000-000000000002",
                )
            database.accounts.activate(
                other.sessionId,
                accountStorageKey(other.audience, other.accountId),
            )
            assertNull(
                assertIs<AppResult.Success<LeaveCommandRecord?>>(repository.find(other, command.id))
                    .value
            )
            assertEquals(AppResult.Success(false), repository.remove(access, original))
            database.accounts.activate(
                access.sessionId,
                accountStorageKey(access.audience, access.accountId),
            )
            assertNull(
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(
                            access.copy(companyId = "20000000-0000-4000-8000-000000000002"),
                            command.id,
                        )
                    )
                    .value
            )
            assertEquals(
                original,
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value,
            )
        }
    }

    @Test
    fun aMissingKeyDoesNotGenerateAReplacementOverRetainedCommands() = runTest {
        withDatabase { database, repository, keys ->
            repository.insert(access, command)
            keys.write(null)
            assertIs<AppResult.Failed>(repository.find(access, command.id))
            assertIs<AppResult.Failed>(
                repository.insert(
                    access,
                    command.copy(
                        id = "30000000-0000-4000-8000-000000000002",
                        requestId = "40000000-0000-4000-8000-000000000002",
                    ),
                )
            )
            assertNull(keys.read())
            assertTrue(database.commands.containsRecords())
        }
    }

    @Test
    fun alteredEnvelopeRevisionsFailClosedWithoutRemovingEvidence() = runTest {
        withDatabase { database, repository, _ ->
            repository.insert(access, command)
            val raw =
                database.commands.get(
                    leaveCommandScope(access),
                    leaveCommandPartition(access),
                    command.id,
                )!!
            assertTrue(
                database.commands.update(
                    leaveCommandScope(access),
                    raw.partition,
                    raw.id,
                    raw.revision,
                    raw.state,
                )
            )
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(repository.find(access, command.id)).failure.kind,
            )
            assertTrue(database.commands.containsRecords())
        }
    }

    @Test
    fun copiedRequestCiphertextCannotBeAssignedToAnotherResource() = runTest {
        withDatabase { database, repository, _ ->
            repository.insert(access, command)
            val raw =
                database.commands.get(
                    leaveCommandScope(access),
                    leaveCommandPartition(access),
                    command.id,
                )!!
            val copied =
                raw.copy(
                    id = "30000000-0000-4000-8000-000000000002",
                    resourceId = "40000000-0000-4000-8000-000000000002",
                    sortKey = "different",
                )
            database.commands.insert(leaveCommandScope(access), copied)
            assertIs<AppResult.Failed>(repository.find(access, copied.id))
            assertIs<AppResult.Success<LeaveCommandRecord?>>(repository.find(access, command.id))
        }
    }

    @Test
    fun onlyOneCompetingOutcomeCanReplaceAnObservedRevision() = runTest {
        withDatabase { _, repository, _ ->
            repository.insert(access, command)
            val original =
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            val results =
                listOf(
                        async {
                            repository.update(
                                access,
                                original,
                                LeaveCommandOutcome.Unconfirmed(),
                                1,
                            )
                        },
                        async {
                            repository.update(
                                access,
                                original,
                                LeaveCommandOutcome.Unconfirmed(),
                                1,
                            )
                        },
                    )
                    .awaitAll()
            assertEquals(1, results.count { it == AppResult.Success(true) })
            assertEquals(1, results.count { it == AppResult.Success(false) })
            val stored =
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            assertEquals(1, stored.revision)
            assertIs<LeaveCommandOutcome.Unconfirmed>(stored.outcome)
            assertEquals(
                stored,
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.observe(access, command.requestId).first()
                    )
                    .value,
            )
        }
    }

    @Test
    fun cancellationDuringEncryptionCannotPublishAnIncompleteOutcome() = runTest {
        withDatabase { database, repository, keys ->
            repository.insert(access, command)
            val original =
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
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
            val pendingRepository = EncryptedLeaveCommandRepository(database.commands, cipher, keys)
            val pending = async {
                pendingRepository.update(access, original, LeaveCommandOutcome.Unconfirmed(), 1)
            }
            entered.await()
            pending.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(
                original,
                assertIs<AppResult.Success<LeaveCommandRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value,
            )
        }
    }

    private suspend fun withDatabase(
        block: suspend (AppDatabase, EncryptedLeaveCommandRepository, MemoryCredentialStore) -> Unit
    ) {
        val directory = Files.createTempDirectory("leave-command").toFile()
        val database = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            database.accounts.activate(
                access.sessionId,
                accountStorageKey(access.audience, access.accountId),
            )
            block(
                database,
                EncryptedLeaveCommandRepository(database.commands, AesGcmContentCipher(), keys),
                keys,
            )
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
