package dev.fajar.starter.payroll.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.payroll.data.replica.encoding.collection
import dev.fajar.starter.payroll.data.replica.mappers.*
import dev.fajar.starter.payroll.data.replica.repositories.EncryptedPayslipReplicaRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.payroll.domain.replica.policies.initialPayrollCheckpoint
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.sync.domain.entities.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.YearMonth

suspend fun checkPayrollIndexedYearsDetailsAndRestart(f: PayrollReplicaFixture) {
    val slips =
        (1..25).map { index ->
            f.slip.copy(
                value =
                    f.slip.value.copy(
                        summary =
                            f.slip.value.summary.copy(
                                id = payrollFixtureId(index),
                                month = YearMonth(if (index < 23) 2025 else 2026, (index % 12) + 1),
                            )
                    )
            )
        }
    f.publish(
        slips.flatMap {
            listOf(it, f.payment.copy(value = f.payment.value.copy(payslipId = it.id)))
        }
    )
    val capturedQueries = mutableListOf<ReplicaQuery>()
    val returned = mutableListOf<String>()
    val store =
        object : ReplicaStore by f.db.replicas {
            override fun observeQueries(
                scope: ReplicaScope,
                partition: String,
                queries: List<ReplicaQuery>,
            ): Flow<ReplicaQueryPage> {
                capturedQueries += queries
                return f.db.replicas.observeQueries(scope, partition, queries).onEach { page ->
                    returned += page.pages.flatten().map { it.id }
                }
            }
        }
    val repo = EncryptedPayslipReplicaRepository(store, f.cipher, f.keys, f.admissions)
    val first =
        assertIs<AppResult.Success<PayrollReplicaOverview>>(
                repo.observe(f.access, PayslipSelection(2025)).first()
            )
            .value
    val after = assertNotNull(first.page.nextCursor)
    val last = f.overview(PayslipSelection(2025, after))
    assertEquals(20, first.page.items.size)
    assertEquals(2, last.page.items.size)
    val expected =
        slips
            .filter { it.value.summary.month.year == 2025 }
            .map { it.value.summary }
            .sortedWith(compareByDescending<PayslipSummary> { it.month }.thenBy { it.id })
    assertEquals(expected, first.page.items + last.page.items)
    assertTrue(returned.all { it.startsWith("summary/") })
    assertTrue(capturedQueries.all { it.tag != null && it.limit == 21 })
    assertNull(last.page.nextCursor)
    assertTrue(f.overview(PayslipSelection(2024)).page.items.isEmpty())
    val id = slips.first().id
    val detail =
        assertIs<AppResult.Success<PayrollReplicaDetails>>(
                repo.observeDetails(f.access, id).first()
            )
            .value
    assertEquals(3, capturedQueries.last().limit)
    assertEquals(slips.first().value, detail.view!!.payslip)
    assertEquals(slips.first().value.summary.takeHome, detail.view!!.payslip.summary.takeHome)
    f.reopen()
    val expiredCredentials =
        f.access.copy(
            sessionExpiresAt = f.raw.now - 7.days,
            authorizationExpiresAt = f.raw.now - 7.days,
        )
    assertEquals(
        expected,
        f.overview(PayslipSelection(2025), expiredCredentials).page.items +
            f.overview(PayslipSelection(2025, after), expiredCredentials).page.items,
    )
    assertEquals(detail.view, f.details(id, expiredCredentials).view)
    assertEquals(f.raw.now, f.details(id).checkpoint!!.refreshedAt)
}

suspend fun checkPayrollIndependentVersionsAndDeletion(f: PayrollReplicaFixture) {
    f.publish(listOf(f.slip, f.payment))
    val scope = payrollReplicaScope(f.access)
    val partition = payrollReplicaPartition(f.access)
    val original = f.db.replicas.document(scope, partition, "payslip/${f.slip.id}")
    f.publish(listOf(f.payment.copy(value = f.payment.value.copy(version = 3))))
    assertEquals(original, f.db.replicas.document(scope, partition, "payslip/${f.slip.id}"))
    assertEquals(3L, f.details().view!!.payments.version)
    f.publish(listOf(f.payment.copy(value = f.payment.value.copy(version = 2))))
    assertEquals(3L, f.details().view!!.payments.version)
    val previous = f.checkpoint()!!
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            previous,
            previous.copy(revision = previous.revision + 1),
            listOf(PayrollReplicaEntry(SyncReference(SyncCollection.PAYSLIPS, f.slip.id, 0), null)),
        ),
    )
    f.publish(listOf(f.slip))
    assertTrue(f.overview().page.items.isEmpty())
    assertNull(f.details().view)
    assertNotNull(f.db.replicas.document(scope, partition, "payments/${f.slip.id}"))
}

suspend fun checkPayrollStagingAndAccountOwnership(f: PayrollReplicaFixture) {
    f.publish(listOf(f.slip, f.payment))
    val old = f.details()
    val initial = initialPayrollCheckpoint(f.access, "replacement", f.raw.now)
    assertEquals(AppResult.Success(true), f.repository.begin(initial, old.checkpoint!!.generation))
    f.publish(
        listOf(f.slip, f.payment.copy(value = f.payment.value.copy(version = 2))),
        publish = false,
    )
    f.reopen()
    assertEquals(old, f.details())
    assertEquals(
        AppResult.Success(old.checkpoint),
        f.repository.observePublishedCheckpoint(f.access).first(),
    )
    f.publish(emptyList())
    assertEquals(2L, f.details().view!!.payments.version)
    val oldWriter = f.checkpoint()!!
    val next = f.access.copy(sessionId = "next-login")
    f.activate(next)
    assertEquals(f.slip.value, f.details(owner = next).view!!.payslip)
    assertEquals(
        AppResult.Success(false),
        f.repository.commit(
            oldWriter,
            oldWriter.copy(revision = oldWriter.revision + 1),
            emptyList(),
        ),
    )
    val other = next.copy(accountId = payrollFixtureId(990), sessionId = "other-account")
    f.activate(other)
    assertNull(f.overview(owner = other).checkpoint)
    f.activate(next)
    assertEquals(2L, f.details(owner = next).view!!.payments.version)
    assertNull(f.details(owner = next.copy(companyId = payrollFixtureId(991))).checkpoint)
    val otherAudience = next.copy(audience = "https://other.example.test/")
    f.activate(otherAudience)
    assertNull(f.details(owner = otherAudience).checkpoint)
}

suspend fun checkPayrollAuthenticatedIndexesAndMissingKey(f: PayrollReplicaFixture) {
    f.publish(listOf(f.slip, f.payment))
    for (component in listOf("payload", "sort", "tags", "id", "version")) {
        val store =
            object : ReplicaStore by f.db.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) =
                    f.db.replicas.observeQueries(scope, partition, queries).map { page ->
                        page.copy(
                            pages =
                                page.pages.map { rows ->
                                    rows.map { row ->
                                        when (component) {
                                            "payload" ->
                                                row.copy(payload = Base64.encode(ByteArray(32)))
                                            "sort" -> row.copy(sortKey = row.sortKey + "x")
                                            "tags" -> row.copy(tags = emptySet())
                                            "id" -> row.copy(id = row.id + "x")
                                            else -> row.copy(version = row.version + 1)
                                        }
                                    }
                                }
                        )
                    }
            }
        val repo = EncryptedPayslipReplicaRepository(store, f.cipher, f.keys, f.admissions)
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(repo.observe(f.access, PayslipSelection(2026)).first())
                .failure
                .kind,
        )
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(repo.observeDetails(f.access, f.slip.id).first())
                .failure
                .kind,
        )
    }
    val checkpoint = f.checkpoint()!!
    f.keys.write(null)
    assertIs<AppResult.Failed>(f.repository.observe(f.access, PayslipSelection(2026)).first())
    assertIs<AppResult.Failed>(
        f.repository.begin(
            initialPayrollCheckpoint(f.access, "new-key", f.raw.now),
            checkpoint.generation,
        )
    )
    assertIs<AppResult.Failed>(f.repository.observePublishedCheckpoint(f.access).first())
    assertNull(f.keys.read())
}

suspend fun checkPayrollCancelledOrObsoleteEncryptionCannotPublish(f: PayrollReplicaFixture) =
    coroutineScope {
        f.publish(listOf(f.slip, f.payment))
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
                EncryptedPayslipReplicaRepository(f.db.replicas, cipher, f.keys, f.admissions)
            val pending = async {
                repo.commit(
                    previous,
                    previous.copy(revision = previous.revision + 1),
                    listOf(
                        PayrollReplicaEntry(
                            SyncReference(SyncCollection.PAYROLL_PAYMENTS, f.slip.id, 3),
                            f.payment.copy(value = f.payment.value.copy(version = 3)),
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

suspend fun checkPayrollMappingFailureDoesNotCommitAnyPartOfTheBatch(f: PayrollReplicaFixture) {
    f.publish(listOf(f.slip, f.payment))
    val previous = f.checkpoint()!!
    val before = f.details()
    val invalid =
        f.slip.copy(
            value =
                f.slip.value.copy(
                    companyName = "",
                    summary = f.slip.value.summary.copy(id = payrollFixtureId(8)),
                )
        )
    val payment = f.payment.copy(value = f.payment.value.copy(version = 2))
    assertIs<AppResult.Failed>(
        f.repository.commit(
            previous,
            previous.copy(revision = previous.revision + 1),
            listOf(payment, invalid).map {
                PayrollReplicaEntry(SyncReference(it.collection(), it.id, it.version), it)
            },
        )
    )
    assertEquals(previous, f.checkpoint())
    assertEquals(before, f.details())
    assertNull(f.details(invalid.id).view)
}

suspend fun checkPayrollEmptyAndStagingReadsDoNotRequireAKeyOrClaimCompletion(
    f: PayrollReplicaFixture
) {
    assertEquals(AppResult.Success(null), f.repository.observePublishedCheckpoint(f.access).first())
    assertNull(f.overview().checkpoint)
    assertNull(f.details().view)
    assertNull(f.keys.read())
    assertEquals(
        AppResult.Success(true),
        f.repository.begin(initialPayrollCheckpoint(f.access, "staging", f.raw.now), null),
    )
    f.publish(listOf(f.slip, f.payment), publish = false)
    assertNull(f.overview().checkpoint)
    assertNull(f.details().view)
    f.publish(emptyList())
    assertNotNull(f.overview().checkpoint)
}

suspend fun checkPayrollRetainedDenialAlsoPreventsKeyRegeneration(f: PayrollReplicaFixture) {
    val denial = Failure(FailureKind.AccessDenied, "Private", code = "mfa_required")
    assertEquals(AppResult.Success(true), f.repository.restrict(f.access, denial))
    assertEquals(AppResult.Success(true), f.repository.clear(f.access))
    f.keys.write(null)
    assertIs<AppResult.Failed>(f.repository.admission(f.access))
    assertIs<AppResult.Failed>(
        f.repository.begin(initialPayrollCheckpoint(f.access, "new-key", f.raw.now), null)
    )
    assertNull(f.keys.read())
}
