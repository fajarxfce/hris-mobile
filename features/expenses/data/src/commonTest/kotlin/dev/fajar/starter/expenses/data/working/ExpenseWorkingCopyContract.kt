@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.data.working

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.replica.*
import dev.fajar.starter.expenses.data.replica.mappers.expenseReplicaScope
import dev.fajar.starter.expenses.data.replica.repositories.EncryptedExpenseReplicaRepository
import dev.fajar.starter.expenses.data.working.encoding.*
import dev.fajar.starter.expenses.data.working.repositories.EncryptedExpenseWorkingCopyRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.replica.policies.initialExpenseCheckpoint
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

fun ExpenseReplicaFixture.workingCopy(): ExpenseWorkingCopy =
    ExpenseWorkingCopy(
        Uuid.random().toString(),
        directory.items.single(),
        access.timezone,
        ExpenseDraftInput("", "Unfinished description", emptyList(), ""),
        null,
        ExpenseWorkingItem(ExpenseLineInput(Uuid.random().toString(), "", "2026-10-13", "12.", "")),
        Uuid.random().toString(),
        raw.now,
    )

fun ExpenseReplicaFixture.workingRepository() =
    EncryptedExpenseWorkingCopyRepository(db.replicas, cipher, keys, Mutex())

suspend fun checkWorkingCopyReopensWithoutChangingClaim(f: ExpenseReplicaFixture) {
    f.publish(listOf(f.claim, f.payment))
    val claim = f.details().snapshot
    val copy = f.workingCopy()
    val slot = ExpenseWorkingSlot.Creation(copy.employee.id)
    assertEquals(AppResult.Success(copy), f.workingRepository().save(f.access, slot, null, copy))
    val raw =
        f.db.replicas
            .observe(
                expenseReplicaScope(f.access),
                expenseWorkingPartition(f.access, slot),
                limit = 5,
            )
            .first()
    assertEquals(4, raw.documents.size)
    assertTrue(raw.documents.all { it.payload?.contains("Unfinished") == false })
    val next = f.access.copy(sessionId = "reauthenticated", sessionExpiresAt = f.raw.now - 73.hours)
    f.reopen(next)
    assertEquals(AppResult.Success(copy), f.workingRepository().observe(next, slot).first())
    assertEquals(claim, f.details(owner = next).snapshot)
    assertEquals(
        AppResult.Success(null),
        f.workingRepository().observe(next.copy(companyId = expenseFixtureId(90)), slot).first(),
    )
    val changed =
        copy.copy(
            revision = Uuid.random().toString(),
            input = copy.input.copy(title = "Old session"),
        )
    assertNotEquals(
        AppResult.Success(changed),
        f.workingRepository().save(f.access, slot, copy.revision, changed),
    )
    assertEquals(AppResult.Success(copy), f.workingRepository().observe(next, slot).first())
}

suspend fun checkWorkingCopyConcurrentWritesAndLostAcknowledgement(f: ExpenseReplicaFixture) =
    coroutineScope {
        val repository = f.workingRepository()
        val copy = f.workingCopy()
        val slot = ExpenseWorkingSlot.Creation(copy.employee.id)
        assertEquals(AppResult.Success(copy), repository.save(f.access, slot, null, copy))
        val proposals =
            List(2) { index ->
                copy.copy(
                    revision = Uuid.random().toString(),
                    input = copy.input.copy(title = "Writer $index"),
                )
            }
        val results =
            proposals
                .map { proposal ->
                    async { repository.save(f.access, slot, copy.revision, proposal) }
                }
                .awaitAll()
        assertEquals(1, results.count { (it as? AppResult.Success)?.value != null })
        val stored =
            assertNotNull(
                assertIs<AppResult.Success<ExpenseWorkingCopy?>>(
                        repository.observe(f.access, slot).first()
                    )
                    .value
            )
        assertEquals(AppResult.Success(false), repository.remove(f.access, slot, copy.revision))
        assertEquals(
            AppResult.Success(stored),
            repository.save(
                f.access,
                slot,
                copy.revision,
                stored.copy(savedAt = stored.savedAt + 3.hours),
            ),
        )
        assertEquals(
            AppResult.Success(null),
            repository.save(
                f.access,
                slot,
                copy.revision,
                stored.copy(input = stored.input.copy(title = "Reused mutation")),
            ),
        )
        assertEquals(AppResult.Success(true), repository.remove(f.access, slot, stored.revision))
        assertEquals(AppResult.Success(null), repository.observe(f.access, slot).first())
    }

suspend fun checkWorkingCopyRejectsMixedChunksAndInvalidInput(f: ExpenseReplicaFixture) {
    val repository = f.workingRepository()
    val copy = f.workingCopy()
    val slot = ExpenseWorkingSlot.Creation(copy.employee.id)
    assertEquals(AppResult.Success(copy), repository.save(f.access, slot, null, copy))
    val changed =
        copy.copy(
            revision = Uuid.random().toString(),
            input = copy.input.copy(description = "測".repeat(2001)),
        )
    assertEquals(
        AppResult.Success(changed),
        repository.save(f.access, slot, copy.revision, changed),
    )
    val invalid =
        changed.copy(
            revision = Uuid.random().toString(),
            item =
                changed.item!!.copy(
                    input = requireNotNull(changed.item).input.copy(amount = "1".repeat(66))
                ),
        )
    assertIs<AppResult.Failed>(repository.save(f.access, slot, changed.revision, invalid))
    assertEquals(AppResult.Success(changed), repository.observe(f.access, slot).first())
    val scope = expenseReplicaScope(f.access)
    val partition = expenseWorkingPartition(f.access, slot)
    val page = f.db.replicas.observe(scope, partition, limit = 5).first()
    val key = Base64.decode(assertNotNull(f.keys.read()))
    try {
        val old = encodeExpenseWorkingCopy(f.access, slot, copy, f.cipher, key)
        assertFails {
            decodeExpenseWorkingCopy(
                f.access,
                slot,
                page.copy(documents = listOf(old.documents.first()) + page.documents.drop(1)),
                f.cipher,
                key,
            )
        }
        assertFails {
            decodeExpenseWorkingCopy(
                f.access,
                ExpenseWorkingSlot.Claim(copy.claimId),
                page,
                f.cipher,
                key,
            )
        }
        assertFails {
            decodeExpenseWorkingCopy(
                f.access.copy(accountId = expenseFixtureId(99)),
                slot,
                page,
                f.cipher,
                key,
            )
        }
        assertFails {
            decodeExpenseWorkingCopy(
                f.access,
                slot,
                page.copy(documents = page.documents.dropLast(1)),
                f.cipher,
                key,
            )
        }
        assertEquals(
            changed,
            decodeExpenseWorkingCopy(
                f.access,
                slot,
                page.copy(documents = page.documents.reversed()),
                f.cipher,
                key,
            ),
        )
    } finally {
        key.fill(0)
    }
}

suspend fun checkWorkingCopyCancellationAndScopeReplacement(f: ExpenseReplicaFixture) =
    coroutineScope {
        val copy = f.workingCopy()
        val slot = ExpenseWorkingSlot.Creation(copy.employee.id)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val borrowed = mutableListOf<ByteArray>()
        val slow =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    borrowed += plaintext
                    reached.complete(Unit)
                    return withContext(NonCancellable) {
                        release.await()
                        f.cipher.encrypt(key, associatedData, plaintext)
                    }
                }
            }
        val repository = EncryptedExpenseWorkingCopyRepository(f.db.replicas, slow, f.keys, Mutex())
        val job = async { repository.save(f.access, slot, null, copy) }
        reached.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(borrowed.all { bytes -> bytes.all { it == 0.toByte() } })
        assertEquals(AppResult.Success(null), f.workingRepository().observe(f.access, slot).first())
        val gated = CompletableDeferred<Unit>()
        val continueWrite = CompletableDeferred<Unit>()
        val replacing =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    gated.complete(Unit)
                    continueWrite.await()
                    return f.cipher.encrypt(key, associatedData, plaintext)
                }
            }
        val late = EncryptedExpenseWorkingCopyRepository(f.db.replicas, replacing, f.keys, Mutex())
        val write = async { late.save(f.access, slot, null, copy) }
        gated.await()
        val replacement = f.access.copy(sessionId = "replacement")
        f.activate(replacement)
        continueWrite.complete(Unit)
        assertEquals(AppResult.Success(null), write.await())
        assertEquals(
            AppResult.Success(null),
            f.workingRepository().observe(replacement, slot).first(),
        )
    }

suspend fun checkWorkingCopySharesItsFirstKeyAndRejectsKeyLoss(f: ExpenseReplicaFixture) =
    coroutineScope {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var generated = 0
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun generateKey(): ByteArray {
                    generated++
                    reached.complete(Unit)
                    release.await()
                    return f.cipher.generateKey()
                }
            }
        val mutex = Mutex()
        val working = EncryptedExpenseWorkingCopyRepository(f.db.replicas, cipher, f.keys, mutex)
        val replicas =
            EncryptedExpenseReplicaRepository(f.db.replicas, cipher, f.keys, f.admissions, mutex)
        val copy = f.workingCopy()
        val slot = ExpenseWorkingSlot.Creation(copy.employee.id)
        val save = async { working.save(f.access, slot, null, copy) }
        reached.await()
        val begin = async {
            replicas.begin(
                initialExpenseCheckpoint(f.access, Uuid.random().toString(), f.raw.now),
                null,
            )
        }
        release.complete(Unit)
        assertEquals(AppResult.Success(copy), save.await())
        assertEquals(AppResult.Success(true), begin.await())
        assertEquals(1, generated)
        assertEquals(AppResult.Success(copy), working.observe(f.access, slot).first())
        replicas.clear(f.access)
        f.keys.write(null)
        assertIs<AppResult.Failed>(
            working.save(
                f.access,
                slot,
                copy.revision,
                copy.copy(revision = Uuid.random().toString()),
            )
        )
        assertIs<AppResult.Failed>(
            replicas.begin(
                initialExpenseCheckpoint(f.access, Uuid.random().toString(), f.raw.now),
                null,
            )
        )
        assertNull(f.keys.read())
        assertEquals(1, generated)
    }
