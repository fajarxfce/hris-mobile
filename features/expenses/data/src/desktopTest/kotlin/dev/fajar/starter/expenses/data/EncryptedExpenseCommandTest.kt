package dev.fajar.starter.expenses.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.data.repositories.EncryptedExpenseCommandRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class EncryptedExpenseCommandTest {
    private val f = ExpenseCommandDataFixture()
    private val access = f.access

    @Test
    fun everyCommandAndReceiptSurvivesDatabaseReopeningAndAccountSessionReplacement() = runTest {
        for (command in f.commands) {
            val directory = Files.createTempDirectory("expense-command-reopen").toFile()
            var database = createAppDatabase(directory)
            val keys = MemoryCredentialStore()
            try {
                database.accounts.activate(
                    access.sessionId,
                    accountStorageKey(access.audience, access.accountId),
                )
                var repository =
                    EncryptedExpenseCommandRepository(
                        database.commands,
                        AesGcmContentCipher(),
                        keys,
                    )
                assertEquals(
                    AppResult.Success(ExpenseCommandAdmission.STORED),
                    repository.insert(access, command),
                )
                val original =
                    assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                            repository.find(access, command.id)
                        )
                        .value!!
                assertEquals(
                    AppResult.Success(true),
                    repository.update(access, original, ExpenseCommandOutcome.Unconfirmed(), 1),
                )
                val sending =
                    assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                            repository.find(access, command.id)
                        )
                        .value!!
                assertEquals(
                    AppResult.Success(true),
                    repository.update(
                        access,
                        sending,
                        ExpenseCommandOutcome.Confirmed(command.expectedReceiptVersion()),
                    ),
                )
                val confirmed =
                    assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                            repository.find(access, command.id)
                        )
                        .value!!
                val raw =
                    database.commands.get(
                        expenseCommandScope(access),
                        expenseCommandPartition(access),
                        command.id,
                    )!!
                assertFalse(
                    raw.request.contains(command.reason) ||
                        raw.request.contains("receiptRevisionIds") ||
                        raw.state.contains("CONFIRMED")
                )
                database.close()
                database = createAppDatabase(directory)
                database.accounts.activate(null)
                val replacement = access.copy(sessionId = "replacement")
                database.accounts.activate(
                    replacement.sessionId,
                    accountStorageKey(replacement.audience, replacement.accountId),
                )
                repository =
                    EncryptedExpenseCommandRepository(
                        database.commands,
                        AesGcmContentCipher(),
                        keys,
                    )
                assertEquals(AppResult.Success(confirmed), repository.find(replacement, command.id))
                assertEquals(
                    AppResult.Success(confirmed),
                    repository.observe(replacement, command.slot()).first(),
                )
                assertEquals(
                    AppResult.Success(ExpenseCommandAdmission.STORED),
                    repository.insert(replacement, command),
                )
                assertEquals(AppResult.Success(confirmed), repository.find(replacement, command.id))
            } finally {
                database.close()
                keys.close()
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun immutableInputAndOneSlotPerRequestPreventCompetingActions() = runTest {
        withDatabase { _, repository, _ ->
            assertEquals(
                AppResult.Success(ExpenseCommandAdmission.STORED),
                repository.insert(access, f.create),
            )
            assertIs<AppResult.Failed>(repository.insert(access, f.create.copy(reason = "Changed")))
            assertEquals(
                AppResult.Success(ExpenseCommandAdmission.OCCUPIED),
                repository.insert(access, f.create.copy(id = f.edit.id)),
            )
            assertEquals(
                AppResult.Success(ExpenseCommandAdmission.STORED),
                repository.insert(access, f.edit),
            )
            assertEquals(
                AppResult.Success(ExpenseCommandAdmission.OCCUPIED),
                repository.insert(access, f.withdraw),
            )
            assertEquals(
                f.edit,
                assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                        repository.observe(access, f.edit.slot()).first()
                    )
                    .value!!
                    .command,
            )
            assertEquals(
                2,
                assertIs<AppResult.Success<ExpensePage<ExpenseCommandRecord>>>(
                        repository.page(access, null)
                    )
                    .value
                    .items
                    .size,
            )
        }
    }

    @Test
    fun outcomeUpdatesUseCompareAndSetAndOldRemovalCannotDeleteAnAttempt() = runTest {
        withDatabase { database, repository, keys ->
            repository.insert(access, f.create)
            val original =
                assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                        repository.find(access, f.create.id)
                    )
                    .value!!
            val second =
                EncryptedExpenseCommandRepository(database.commands, AesGcmContentCipher(), keys)
            val updates =
                awaitAll(
                    async {
                        repository.update(access, original, ExpenseCommandOutcome.Unconfirmed(), 1)
                    },
                    async {
                        second.update(access, original, ExpenseCommandOutcome.Unconfirmed(), 1)
                    },
                )
            assertEquals(1, updates.count { it == AppResult.Success(true) })
            assertEquals(1, updates.count { it == AppResult.Success(false) })
            assertEquals(AppResult.Success(false), repository.remove(access, original))
            val current =
                assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                        repository.find(access, f.create.id)
                    )
                    .value!!
            assertEquals(1, current.revision)
            assertEquals(1, current.attempts)
            assertIs<ExpenseCommandOutcome.Unconfirmed>(current.outcome)
        }
    }

    @Test
    fun commandsCannotBeReadOrMutatedAcrossAccountAudienceOrCompany() = runTest {
        withDatabase { database, repository, _ ->
            repository.insert(access, f.create)
            val original =
                assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                        repository.find(access, f.create.id)
                    )
                    .value!!
            val other =
                access.copy(sessionId = "other", accountId = "99999999-9999-9999-9999-999999999999")
            database.accounts.activate(
                other.sessionId,
                accountStorageKey(other.audience, other.accountId),
            )
            assertEquals(AppResult.Success(null), repository.find(other, f.create.id))
            assertEquals(AppResult.Success(false), repository.remove(access, original))
            database.accounts.activate(
                access.sessionId,
                accountStorageKey(access.audience, access.accountId),
            )
            assertEquals(
                AppResult.Success(null),
                repository.find(access.copy(audience = "https://other.example.test/"), f.create.id),
            )
            val company = access.copy(companyId = "88888888-8888-8888-8888-888888888888")
            assertEquals(AppResult.Success(null), repository.find(company, f.create.id))
            assertIs<AppResult.Failed>(
                repository.update(company, original, ExpenseCommandOutcome.Unconfirmed(), 1)
            )
            assertIs<AppResult.Failed>(repository.remove(company, original))
            assertEquals(AppResult.Success(original), repository.find(access, f.create.id))
        }
    }

    @Test
    fun authenticatedCompanyPurposeAndRevisionRejectCopiedCiphertext() = runTest {
        withDatabase { database, repository, _ ->
            repository.insert(access, f.create)
            val original =
                assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                        repository.find(access, f.create.id)
                    )
                    .value!!
            val raw =
                database.commands.get(
                    expenseCommandScope(access),
                    expenseCommandPartition(access),
                    f.create.id,
                )!!
            val other = access.copy(companyId = "88888888-8888-8888-8888-888888888888")
            assertEquals(
                CommandInsertResult.Stored,
                database.commands.insert(
                    expenseCommandScope(other),
                    raw.copy(partition = expenseCommandPartition(other)),
                ),
            )
            assertIs<AppResult.Failed>(repository.find(other, f.create.id))
            repository.update(access, original, ExpenseCommandOutcome.Unconfirmed(), 1)
            assertTrue(
                database.commands.update(
                    expenseCommandScope(access),
                    expenseCommandPartition(access),
                    f.create.id,
                    1,
                    raw.state,
                )
            )
            assertIs<AppResult.Failed>(repository.find(access, f.create.id))
        }
    }

    @Test
    fun aMissingContentKeyNeverReplacesRetainedCiphertext() = runTest {
        withDatabase { database, repository, _ ->
            repository.insert(access, f.create)
            val absent = MemoryCredentialStore()
            try {
                val reader =
                    EncryptedExpenseCommandRepository(
                        database.commands,
                        AesGcmContentCipher(),
                        absent,
                    )
                assertIs<AppResult.Failed>(reader.find(access, f.create.id))
                assertIs<AppResult.Failed>(reader.insert(access, f.edit))
                assertNull(absent.read())
                assertEquals(
                    f.create,
                    assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                            repository.find(access, f.create.id)
                        )
                        .value!!
                        .command,
                )
            } finally {
                absent.close()
            }
        }
    }

    @Test
    fun cancellationAndAccountReplacementAfterEncryptionCannotPublishAndClearBuffers() = runTest {
        for (updating in listOf(false, true)) for (cancel in listOf(false, true)) {
            withDatabase { database, repository, keys ->
                if (updating) repository.insert(access, f.create)
                val original =
                    assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                            repository.find(access, f.create.id)
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
                    EncryptedExpenseCommandRepository(database.commands, cipher, keys)
                val pending = async {
                    if (updating)
                        pendingRepository.update(
                            access,
                            original!!,
                            ExpenseCommandOutcome.Unconfirmed(),
                            1,
                        )
                    else pendingRepository.insert(access, f.create)
                }
                entered.await()
                if (cancel) pending.cancel()
                else database.accounts.activate("replacement", "another-account")
                release.complete(Unit)
                if (cancel) assertFailsWith<CancellationException> { pending.await() }
                else
                    assertEquals(
                        if (updating) AppResult.Success(false)
                        else AppResult.Success(ExpenseCommandAdmission.SCOPE_CHANGED),
                        pending.await(),
                    )
                database.accounts.activate(
                    access.sessionId,
                    accountStorageKey(access.audience, access.accountId),
                )
                assertEquals(AppResult.Success(original), repository.find(access, f.create.id))
                assertTrue(buffers.isNotEmpty())
                assertTrue(buffers.all { buffer -> buffer.all { it == 0.toByte() } })
            }
        }
    }

    @Test
    fun encryptedPaginationRemainsOrderedAndBoundedWithoutRepeatingACommand() = runTest {
        withDatabase { _, repository, _ ->
            for (index in 1..51) {
                val id = "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                assertEquals(
                    AppResult.Success(ExpenseCommandAdmission.STORED),
                    repository.insert(access, f.create.copy(id = id, claimId = id, employeeId = id)),
                )
            }
            val first =
                assertIs<AppResult.Success<ExpensePage<ExpenseCommandRecord>>>(
                        repository.page(access, null)
                    )
                    .value
            assertEquals(50, first.items.size)
            assertNotNull(first.nextCursor)
            val second =
                assertIs<AppResult.Success<ExpensePage<ExpenseCommandRecord>>>(
                        repository.page(access, first.nextCursor)
                    )
                    .value
            assertEquals(1, second.items.size)
            assertNull(second.nextCursor)
            assertEquals(51, (first.items + second.items).map { it.command.id }.toSet().size)
            assertTrue(
                first.items.last().command.orderKey() < second.items.single().command.orderKey()
            )
        }
    }

    @Test
    fun oversizedStoredEnvelopesAreRejectedBeforeDecryption() = runTest {
        withDatabase { database, repository, keys ->
            repository.insert(access, f.create)
            val raw =
                database.commands.get(
                    expenseCommandScope(access),
                    expenseCommandPartition(access),
                    f.create.id,
                )!!
            var calls = 0
            val cipher =
                object : ContentCipher by AesGcmContentCipher() {
                    override suspend fun decrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        ciphertext: ByteArray,
                    ): ByteArray {
                        calls++
                        error("Oversized data must not reach the cipher")
                    }
                }
            for (oversized in
                listOf(
                    raw.copy(request = "x".repeat(98_305)),
                    raw.copy(state = "x".repeat(8_193)),
                )) {
                val source =
                    object : CommandStore by database.commands {
                        override suspend fun get(
                            scope: CommandScope,
                            partition: String,
                            id: String,
                        ) = oversized
                    }
                assertIs<AppResult.Failed>(
                    EncryptedExpenseCommandRepository(source, cipher, keys)
                        .find(access, f.create.id)
                )
            }
            assertEquals(0, calls)
        }
    }

    @Test
    fun twentyUnicodeLinesWithEvidenceFitTheBoundedEncryptedRequest() = runTest {
        withDatabase { _, repository, _ ->
            val original = f.create.payload as ExpenseCommandPayload.SaveDraft
            val lines =
                List(20) { index ->
                    original.lines
                        .single()
                        .copy(
                            id = "30000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                            description = "漢".repeat(500),
                            receiptIds = listOf(f.f.claim.id, f.f.line.id, f.f.receipt.revisionId),
                        )
                }
            val command =
                f.create.copy(
                    reason = "漢".repeat(1000),
                    payload =
                        original.copy(
                            title = "漢".repeat(160),
                            description = "漢".repeat(2000),
                            lines = lines,
                        ),
                )
            assertEquals(
                AppResult.Success(ExpenseCommandAdmission.STORED),
                repository.insert(access, command),
            )
            val restored =
                assertIs<AppResult.Success<ExpenseCommandRecord?>>(
                        repository.find(access, command.id)
                    )
                    .value!!
            assertEquals(command, restored.command)
        }
    }

    private suspend fun withDatabase(
        block:
            suspend (AppDatabase, EncryptedExpenseCommandRepository, MemoryCredentialStore) -> Unit
    ) {
        val directory = Files.createTempDirectory("expense-command").toFile()
        val database = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            database.accounts.activate(
                access.sessionId,
                accountStorageKey(access.audience, access.accountId),
            )
            block(
                database,
                EncryptedExpenseCommandRepository(database.commands, AesGcmContentCipher(), keys),
                keys,
            )
        } finally {
            database.close()
            keys.close()
            directory.deleteRecursively()
        }
    }
}
