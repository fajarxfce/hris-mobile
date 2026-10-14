@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.data.replica

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.data.replica.encoding.*
import dev.fajar.starter.expenses.data.replica.mappers.*
import dev.fajar.starter.expenses.data.replica.repositories.EncryptedExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.expenses.domain.replica.policies.initialExpenseCheckpoint
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.sync.domain.entities.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.YearMonth

suspend fun checkExpenseLocalMonthsPagesAndReauthentication(f: ExpenseReplicaFixture) {
    val records =
        (0..26).flatMap { index ->
            val id = expenseFixtureId(index)
            val record =
                f.claim.snapshot.value.copy(
                    claim =
                        f.claim.snapshot.value.claim.copy(
                            id = id,
                            createdAt =
                                if (index < 25) f.raw.now else Instant.parse("2026-09-20T00:00:00Z"),
                        )
                )
            listOf(
                ExpenseRecord.Claim(
                    f.claim.snapshot.copy(generation = Uuid.random().toString(), value = record)
                ),
                ExpenseRecord.Payments(ExpenseClaimPayments(id, 0, emptyList())),
            )
        }
    f.publish(records)
    val first = f.overview()
    assertEquals(20, first.page.items.size)
    assertEquals((0..19).map(::expenseFixtureId), first.page.items.map { it.id })
    val second = f.overview(f.selection.copy(after = assertNotNull(first.page.nextCursor)))
    assertEquals(5, second.page.items.size)
    assertNull(second.page.nextCursor)
    val earlier = f.overview(f.selection.copy(month = YearMonth(2026, 9)))
    assertEquals(2, earlier.page.items.size)
    assertTrue(f.overview(f.selection.copy(status = ExpenseStatus.REJECTED)).page.items.isEmpty())
    val saved = f.details(expenseFixtureId(1)).snapshot!!
    f.reopen(f.access.copy(sessionId = "next-login", sessionExpiresAt = f.raw.now + 100.hours))
    val owner = f.access.copy(sessionId = "next-login", sessionExpiresAt = f.raw.now + 100.hours)
    assertEquals(saved, f.details(expenseFixtureId(1), owner).snapshot)
    assertEquals(20, f.overview(owner = owner).page.items.size)
    assertEquals(2, f.overview(f.selection.copy(month = YearMonth(2026, 9)), owner).page.items.size)
    assertTrue(
        f.overview(owner = owner.copy(companyId = expenseFixtureId(99))).page.items.isEmpty()
    )
}

suspend fun checkExpenseEqualVersionGrowthAndShrink(f: ExpenseReplicaFixture) {
    val large = largeExpenseClaimRecord(f)
    val small = smallExpenseClaimRecord(f)
    f.publish(listOf(small, f.payment))
    f.publish(listOf(large))
    assertEquals(large.snapshot.value.claim, f.details().snapshot!!.value.claim)
    f.publish(listOf(small))
    assertEquals(small.snapshot.value.claim, f.details().snapshot!!.value.claim)
    f.publish(listOf(large))
    assertEquals(large.snapshot.value.claim, f.details().snapshot!!.value.claim)
    f.reopen()
    assertEquals(large.snapshot.value.claim, f.details().snapshot!!.value.claim)
}

suspend fun checkExpenseAuthenticatedChunks(f: ExpenseReplicaFixture) {
    val large = largeExpenseClaimRecord(f)
    val small = smallExpenseClaimRecord(f)
    f.publish(listOf(large, f.payment))
    val cp = f.checkpoint()!!
    val key = Base64.decode(requireNotNull(f.keys.read()))
    try {
        fun List<ReplicaDocument>.parts() = filter { it.id.startsWith("claim/") }
        val old = encodeExpenseClaimRecords(large.snapshot, cp, f.cipher, key).parts()
        val next = encodeExpenseClaimRecords(small.snapshot, cp, f.cipher, key).parts()
        assertEquals(17, old.size)
        assertTrue(old.all { it.payload != null })
        assertTrue(old.all { it.payload!!.length <= 131_072 })
        assertFails {
            decodeExpenseClaimRecords(
                listOf(next.first()) + old.drop(1),
                f.access,
                cp.generation,
                f.raw.claim.id,
                f.cipher,
                key,
            )
        }
        assertFails {
            decodeExpenseClaimRecords(
                old.dropLast(1),
                f.access,
                cp.generation,
                f.raw.claim.id,
                f.cipher,
                key,
            )
        }
        assertFails {
            decodeExpenseClaimRecords(
                old.mapIndexed { index, document ->
                    if (index == 1) document.copy(tags = emptySet()) else document
                },
                f.access,
                cp.generation,
                f.raw.claim.id,
                f.cipher,
                key,
            )
        }
        assertEquals(
            large.snapshot,
            decodeExpenseClaimRecords(
                old.reversed(),
                f.access,
                cp.generation,
                f.raw.claim.id,
                f.cipher,
                key,
            ),
        )
    } finally {
        key.fill(0)
    }
}

suspend fun checkExpenseHistoryKeepsReviewLeaseAndCannotSkipItsFrontier(f: ExpenseReplicaFixture) {
    val record =
        f.claim.copy(
            snapshot =
                f.claim.snapshot.copy(
                    value =
                        f.claim.snapshot.value.copy(
                            claim = f.claim.snapshot.value.claim.copy(version = 3),
                            history =
                                ExpensePage(listOf(f.raw.change.copy(version = 3).toChange()), "3"),
                        )
                )
        )
    f.publish(listOf(record, f.payment))
    val before = f.details().snapshot!!
    val work = (f.repository.pendingHistory(f.access) as AppResult.Success).value.single()
    val arbitrary =
        ExpenseHistoryPage(
            f.raw.claim.id,
            3,
            2,
            ExpensePage(listOf(f.raw.change.copy(version = 1).toChange()), null),
        )
    assertEquals(
        AppResult.Success(true),
        f.repository.storeHistory(f.access, work.copy(after = 2), arbitrary),
    )
    assertEquals(work, (f.repository.pendingHistory(f.access) as AppResult.Success).value.single())
    val page =
        ExpenseHistoryPage(
            f.raw.claim.id,
            3,
            3,
            ExpensePage(listOf(f.raw.change.copy(version = 2).toChange()), "2"),
        )
    assertEquals(AppResult.Success(true), f.repository.storeHistory(f.access, work, page))
    val next = (f.repository.pendingHistory(f.access) as AppResult.Success).value.single()
    assertEquals(2, next.after)
    assertEquals(before, f.details().snapshot)
    assertEquals(page.history, f.details(after = 3).snapshot!!.value.history)
    assertEquals(before.fetchedAt, f.details(after = 3).snapshot!!.fetchedAt)
    assertEquals(before.refreshAfter, f.details(after = 2).snapshot!!.refreshAfter)
    assertEquals(AppResult.Success(true), f.repository.storeHistory(f.access, next, arbitrary))
    assertTrue((f.repository.pendingHistory(f.access) as AppResult.Success).value.isEmpty())
    f.publish(
        listOf(record.copy(snapshot = record.snapshot.copy(generation = Uuid.random().toString())))
    )
    assertEquals(AppResult.Success(false), f.repository.storeHistory(f.access, work, page))
    f.reopen()
    assertEquals(arbitrary.history, f.details(after = 2).snapshot!!.value.history)
}

suspend fun checkExpenseStagingDeletionAndLostKeys(f: ExpenseReplicaFixture) {
    assertNull(f.details().snapshot)
    assertNull(f.keys.read())
    f.publish(listOf(f.claim, f.payment))
    val saved = f.details().snapshot
    val replacement = initialExpenseCheckpoint(f.access, Uuid.random().toString(), f.raw.now)
    assertEquals(
        AppResult.Success(true),
        f.repository.begin(replacement, f.checkpoint()!!.generation),
    )
    assertEquals(saved, f.details().snapshot)
    f.publish(listOf(f.claim, f.payment), publish = false)
    assertEquals(saved, f.details().snapshot)
    f.publish(emptyList())
    val cp = f.checkpoint()!!
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            cp,
            cp.copy(revision = cp.revision + 1),
            f.directory,
            listOf(
                ExpenseReplicaEntry(
                    SyncReference(SyncCollection.EXPENSE_CLAIMS, f.raw.claim.id, 1),
                    null,
                )
            ),
        ),
    )
    assertNull(f.details().snapshot)
    assertTrue(f.overview().page.items.isEmpty())
    val denial = Failure(FailureKind.AccessDenied, "Private", code = "mfa_required")
    assertEquals(AppResult.Success(true), f.repository.restrict(f.access, denial))
    assertEquals(AppResult.Success(true), f.repository.clear(f.access))
    f.keys.write(null)
    assertIs<AppResult.Failed>(f.repository.admission(f.access))
    assertIs<AppResult.Failed>(
        f.repository.begin(initialExpenseCheckpoint(f.access, "replacement", f.raw.now), null)
    )
    assertNull(f.keys.read())
}

suspend fun checkExpenseAtomicMappingFailureAndPaymentDeletion(f: ExpenseReplicaFixture) {
    f.publish(listOf(f.claim, f.payment))
    val cp = f.checkpoint()!!
    val before = f.details()
    val invalid =
        f.claim.copy(
            snapshot =
                f.claim.snapshot.copy(
                    value =
                        f.claim.snapshot.value.copy(
                            claim =
                                f.claim.snapshot.value.claim.copy(
                                    draft =
                                        f.claim.snapshot.value.claim.draft.copy(
                                            total = DecimalAmount.parse("9")
                                        )
                                )
                        )
                )
        )
    assertIs<AppResult.Failed>(
        f.repository.commit(
            cp,
            cp.copy(revision = cp.revision + 1, cursor = "later"),
            f.directory,
            listOf(expenseTestEntry(f.payment), expenseTestEntry(invalid)),
        )
    )
    assertEquals(cp, f.checkpoint())
    assertEquals(before, f.details())
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            cp,
            cp.copy(revision = cp.revision + 1),
            f.directory,
            listOf(
                ExpenseReplicaEntry(
                    SyncReference(SyncCollection.EXPENSE_PAYMENTS, f.raw.claim.id, 1),
                    null,
                )
            ),
        ),
    )
    assertEquals(before.snapshot, f.details().snapshot)
}

suspend fun checkExpenseCancelledOrObsoleteEncryptionCannotPublish(f: ExpenseReplicaFixture) =
    coroutineScope {
        f.publish(listOf(f.claim, f.payment))
        for (cancel in listOf(false, true)) {
            val previous = f.checkpoint()!!
            val before = f.details()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val keys = mutableListOf<ByteArray>()
            val plaintexts = mutableListOf<ByteArray>()
            val cipher =
                object : ContentCipher by f.cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray {
                        keys += key
                        plaintexts += plaintext
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        return f.cipher.encrypt(key, associatedData, plaintext)
                    }
                }
            val repo =
                EncryptedExpenseReplicaRepository(f.db.replicas, cipher, f.keys, f.admissions)
            val pending = async {
                repo.commit(
                    previous,
                    previous.copy(revision = previous.revision + 1),
                    f.directory,
                    listOf(
                        expenseTestEntry(
                            f.claim.copy(
                                snapshot =
                                    f.claim.snapshot.copy(generation = Uuid.random().toString())
                            )
                        )
                    ),
                )
            }
            entered.await()
            if (cancel) pending.cancel() else f.db.accounts.activate("replacement")
            release.complete(Unit)
            if (cancel) assertFailsWith<CancellationException> { pending.await() }
            else assertEquals(AppResult.Success(false), pending.await())
            f.activate()
            assertEquals(before, f.details())
            assertTrue(keys.isNotEmpty() && keys.all { bytes -> bytes.all { it == 0.toByte() } })
            assertTrue(plaintexts.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }
