package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.securestorage.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class EncryptedLeaveSubmissionTest {
    private val fixture = LeaveSubmissionDataFixture()
    private val access = fixture.access
    private val command = fixture.command

    @Test
    fun inputPreviewRevisionAndReceiptSurviveReopeningForTheSameAccount() = runTest {
        val directory = Files.createTempDirectory("leave-submission-reopen").toFile()
        var database = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            database.accounts.activate(
                access.sessionId,
                accountStorageKey(access.audience, access.accountId),
            )
            var repository =
                EncryptedLeaveSubmissionCommandRepository(
                    database.commands,
                    AesGcmContentCipher(),
                    keys,
                )
            assertEquals(
                AppResult.Success(LeaveCommandAdmission.STORED),
                repository.insert(access, command),
            )
            val original =
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            assertEquals(
                AppResult.Success(true),
                repository.update(access, original, LeaveCommandOutcome.Unconfirmed(), 1),
            )
            val sending =
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            assertEquals(
                AppResult.Success(true),
                repository.update(access, sending, LeaveCommandOutcome.Confirmed(0)),
            )
            val confirmed =
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            val raw =
                database.commands.get(
                    leaveCommandScope(access),
                    leaveSubmissionPartition(access),
                    command.id,
                )!!
            assertFalse(raw.request.contains(command.reason))
            assertFalse(raw.request.contains(command.previewRevision))
            assertFalse(raw.state.contains("CONFIRMED"))
            database.close()
            database = createAppDatabase(directory)
            database.accounts.activate(null)
            assertNull(
                database.commands.get(
                    leaveCommandScope(access),
                    leaveSubmissionPartition(access),
                    command.id,
                )
            )
            val replacement = access.copy(sessionId = "replacement")
            database.accounts.activate(
                replacement.sessionId,
                accountStorageKey(replacement.audience, replacement.accountId),
            )
            repository =
                EncryptedLeaveSubmissionCommandRepository(
                    database.commands,
                    AesGcmContentCipher(),
                    keys,
                )
            assertEquals(
                confirmed,
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
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
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                        repository.observe(replacement, command.selection.employeeId).first()
                    )
                    .value,
            )
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aSecondSubmissionCannotReplaceRetainedInputButRequestActionsHaveTheirOwnPartition() =
        runTest {
            withDatabase { database, repository, keys ->
                assertEquals(
                    AppResult.Success(LeaveCommandAdmission.STORED),
                    repository.insert(access, command),
                )
                assertIs<AppResult.Failed>(
                    repository.insert(access, command.copy(reason = "Changed"))
                )
                val second =
                    command.copy(
                        id = "30000000-0000-4000-8000-000000000002",
                        requestId = "40000000-0000-4000-8000-000000000002",
                    )
                assertEquals(
                    AppResult.Success(LeaveCommandAdmission.OCCUPIED),
                    repository.insert(access, second),
                )
                assertEquals(
                    command,
                    assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                            repository.find(access, command.id)
                        )
                        .value!!
                        .command,
                )
                val actions =
                    EncryptedLeaveCommandRepository(database.commands, AesGcmContentCipher(), keys)
                val action =
                    LeaveCommand(
                        command.id,
                        access.accountId,
                        access.audience,
                        access.companyId,
                        command.requestId,
                        0,
                        LeaveRequestAction.WITHDRAW,
                        "Changed plans",
                        command.createdAt,
                    )
                assertEquals(
                    AppResult.Success(LeaveCommandAdmission.STORED),
                    actions.insert(access, action),
                )
                assertEquals(
                    action,
                    assertIs<AppResult.Success<LeaveCommandRecord?>>(
                            actions.find(access, action.id)
                        )
                        .value!!
                        .command,
                )
                assertEquals(
                    command,
                    assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                            repository.find(access, command.id)
                        )
                        .value!!
                        .command,
                )
                keys.write(null)
                assertIs<AppResult.Failed>(repository.insert(access, second))
                assertNull(keys.read())
            }
        }

    @Test
    fun aSubmissionCannotGenerateANewKeyOverAnotherPartitionsRetainedCiphertext() = runTest {
        withDatabase { database, repository, keys ->
            val actions =
                EncryptedLeaveCommandRepository(database.commands, AesGcmContentCipher(), keys)
            val action =
                LeaveCommand(
                    command.id,
                    access.accountId,
                    access.audience,
                    access.companyId,
                    command.requestId,
                    0,
                    LeaveRequestAction.WITHDRAW,
                    "Changed plans",
                    command.createdAt,
                )
            assertEquals(
                AppResult.Success(LeaveCommandAdmission.STORED),
                actions.insert(access, action),
            )
            keys.write(null)
            assertIs<AppResult.Failed>(repository.insert(access, command))
            assertNull(keys.read())
            assertTrue(database.commands.containsRecords())
            assertNull(
                database.commands.get(
                    leaveCommandScope(access),
                    leaveSubmissionPartition(access),
                    command.id,
                )
            )
        }
    }

    @Test
    fun aReplacementAccountOrCompanyCannotReadOrRemoveTheSubmission() = runTest {
        withDatabase { database, repository, _ ->
            repository.insert(access, command)
            val original =
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            val other =
                access.copy(sessionId = "other", accountId = "10000000-0000-4000-8000-000000000002")
            database.accounts.activate(
                other.sessionId,
                accountStorageKey(other.audience, other.accountId),
            )
            assertNull(
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                        repository.find(other, command.id)
                    )
                    .value
            )
            assertEquals(AppResult.Success(false), repository.remove(access, original))
            database.accounts.activate(
                access.sessionId,
                accountStorageKey(access.audience, access.accountId),
            )
            val company = access.copy(companyId = "20000000-0000-4000-8000-000000000002")
            assertNull(
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                        repository.find(company, command.id)
                    )
                    .value
            )
            assertEquals(AppResult.Success(false), repository.remove(company, original))
            assertEquals(
                original,
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value,
            )
        }
    }

    @Test
    fun copiedCiphertextAndAlteredOutcomeRevisionsFailClosedWithoutDeletingEvidence() = runTest {
        withDatabase { database, repository, _ ->
            repository.insert(access, command)
            val scope = leaveCommandScope(access)
            val raw = database.commands.get(scope, leaveSubmissionPartition(access), command.id)!!
            val copied =
                raw.copy(
                    id = "30000000-0000-4000-8000-000000000002",
                    resourceId = "40000000-0000-4000-8000-000000000002",
                    sortKey = "different",
                )
            assertEquals(CommandInsertResult.Stored, database.commands.insert(scope, copied))
            assertIs<AppResult.Failed>(repository.find(access, copied.id))
            assertIs<AppResult.Success<LeaveSubmissionRecord?>>(repository.find(access, command.id))
            assertTrue(
                database.commands.update(scope, raw.partition, raw.id, raw.revision, raw.state)
            )
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(repository.find(access, command.id)).failure.kind,
            )
            assertNotNull(database.commands.get(scope, raw.partition, command.id))
            assertNotNull(database.commands.get(scope, raw.partition, copied.id))
        }
    }

    @Test
    fun aReceiptMustMatchCreationAndCompetingOutcomesCannotBothWin() = runTest {
        withDatabase { _, repository, _ ->
            repository.insert(access, command)
            val original =
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            assertIs<AppResult.Failed>(
                repository.update(access, original, LeaveCommandOutcome.Confirmed(1), 1)
            )
            assertIs<AppResult.Failed>(
                repository.update(access, original, LeaveCommandOutcome.Confirmed(0), 0)
            )
            assertEquals(
                original,
                assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value,
            )
            val outcomes =
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
            assertEquals(1, outcomes.count { it == AppResult.Success(true) })
            assertEquals(1, outcomes.count { it == AppResult.Success(false) })
            assertEquals(AppResult.Success(false), repository.remove(access, original))
            val stored =
                assertIs<AppResult.Success<LeavePage<LeaveSubmissionRecord>>>(
                        repository.page(access, null)
                    )
                    .value
                    .items
                    .single()
            assertEquals(1, stored.revision)
            assertIs<LeaveCommandOutcome.Unconfirmed>(stored.outcome)
        }
    }

    @Test
    fun cancellationAfterNonCooperativeEncryptionCannotPublishInputOrOutcomeAndClearsBuffers() =
        runTest {
            for (updating in listOf(false, true)) {
                withDatabase { database, repository, keys ->
                    if (updating) repository.insert(access, command)
                    val original =
                        assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                                repository.find(access, command.id)
                            )
                            .value
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val buffers = mutableListOf<ByteArray>()
                    val delegate = AesGcmContentCipher()
                    val cipher =
                        object : ContentCipher by delegate {
                            override suspend fun encrypt(
                                key: ByteArray,
                                associatedData: ByteArray,
                                plaintext: ByteArray,
                            ): ByteArray {
                                buffers += key
                                buffers += plaintext
                                entered.complete(Unit)
                                return withContext(NonCancellable) {
                                    release.await()
                                    delegate.encrypt(key, associatedData, plaintext)
                                }
                            }
                        }
                    val pendingRepository =
                        EncryptedLeaveSubmissionCommandRepository(database.commands, cipher, keys)
                    val pending = async {
                        if (updating)
                            pendingRepository.update(
                                access,
                                original!!,
                                LeaveCommandOutcome.Unconfirmed(),
                                1,
                            )
                        else pendingRepository.insert(access, command)
                    }
                    entered.await()
                    pending.cancel()
                    release.complete(Unit)
                    assertFailsWith<CancellationException> { pending.await() }
                    assertEquals(
                        original,
                        assertIs<AppResult.Success<LeaveSubmissionRecord?>>(
                                repository.find(access, command.id)
                            )
                            .value,
                    )
                    assertTrue(buffers.isNotEmpty())
                    assertTrue(buffers.all { bytes -> bytes.all { it == 0.toByte() } })
                }
            }
        }

    private suspend fun withDatabase(
        block:
            suspend (
                AppDatabase, EncryptedLeaveSubmissionCommandRepository, MemoryCredentialStore,
            ) -> Unit
    ) {
        val directory = Files.createTempDirectory("leave-submission").toFile()
        val database = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            database.accounts.activate(
                access.sessionId,
                accountStorageKey(access.audience, access.accountId),
            )
            block(
                database,
                EncryptedLeaveSubmissionCommandRepository(
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
