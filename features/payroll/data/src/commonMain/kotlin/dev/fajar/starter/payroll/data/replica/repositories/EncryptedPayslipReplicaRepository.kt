@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.payroll.data.replica.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.payroll.data.dto.*
import dev.fajar.starter.payroll.data.encoding.payslipPartition
import dev.fajar.starter.payroll.data.mappers.*
import dev.fajar.starter.payroll.data.replica.datasources.PayrollReadAdmissionDataSource
import dev.fajar.starter.payroll.data.replica.dto.PayrollReadAdmissionDto
import dev.fajar.starter.payroll.data.replica.encoding.*
import dev.fajar.starter.payroll.data.replica.mappers.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.payroll.domain.replica.repositories.PayslipReplicaRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedPayslipReplicaRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("payroll-replica-content-key") private val credentials: CredentialStore,
    private val admissions: PayrollReadAdmissionDataSource,
) : PayslipReplicaRepository {
    private val keyCreation = Mutex()
    private val admissionWrites = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdmission(access: PayrollAccess) =
        admissions
            .observe(payrollAdmissionBufferKey(access))
            .flatMapLatest { pending ->
                if (pending != null)
                    safeStorageFlow(flowOf(pending), mapValue = { it.toReadAdmission() })
                else
                    safeStorageFlow(
                        replicas.observeCheckpoint(
                            payrollReplicaScope(access),
                            payrollAdmissionPartition(access),
                        ),
                        mapValue = { raw ->
                            if (raw == null) PayrollReadAdmission(null, null)
                            else {
                                val key = Base64.decode(requireNotNull(credentials.read()))
                                try {
                                    decodePayrollReadAdmission(cipher, key, access, raw)
                                        .toReadAdmission()
                                } finally {
                                    key.fill(0)
                                }
                            }
                        },
                    )
            }
            .distinctUntilChanged()

    override suspend fun admission(access: PayrollAccess) = observeAdmission(access).first()

    override suspend fun restrict(access: PayrollAccess, failure: Failure) = safeStorageCall {
        val bufferKey = payrollAdmissionBufferKey(access)
        val dto = PayrollReadAdmissionDto(Uuid.random().toString(), failure.kind.name, failure.code)
        dto.toReadAdmission()
        // Publish the confirmed denial even when the following durable write cannot complete.
        admissions.write(bufferKey, dto)
        admissionWrites.withLock {
            val scope = payrollReplicaScope(access)
            val partition = payrollAdmissionPartition(access)
            val previous = replicas.checkpoint(scope, partition)
            val key =
                keyCreation.withLock {
                    readOrCreatePayrollReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodePayrollReadAdmission(cipher, key, access, dto)
                currentCoroutineContext().ensureActive()
                val saved =
                    replicas.replace(
                        scope,
                        partition,
                        previous,
                        ReplicaSnapshot(dto.revision, sealed, emptyList()),
                    )
                if (saved && admissions.current(bufferKey) == dto) admissions.write(bufferKey, null)
                saved
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun admit(access: PayrollAccess, expectedRevision: String?) = safeStorageCall {
        admissionWrites.withLock {
            val bufferKey = payrollAdmissionBufferKey(access)
            val scope = payrollReplicaScope(access)
            val partition = payrollAdmissionPartition(access)
            val previous = replicas.checkpoint(scope, partition)
            val pending = admissions.current(bufferKey)
            if ((pending?.revision ?: previous?.handle?.generation) != expectedRevision)
                return@withLock false
            currentCoroutineContext().ensureActive()
            if (!replicas.replace(scope, partition, previous, null)) return@withLock false
            // A denial arriving while storage was pending belongs to a later acquisition.
            if (admissions.current(bufferKey) != pending) return@withLock false
            admissions.write(bufferKey, null)
            true
        }
    }

    override fun observe(access: PayrollAccess, selection: PayslipSelection) =
        safeStorageFlow(
            flow {
                require(
                    validatePayslipSelection(selection) is AppResult.Success &&
                        selection.year != null
                )
                emitAll(
                    observeIndexedRecords(
                        access,
                        "year",
                        selection.year.toString(),
                        payrollSummaryAfter(selection),
                        PAYSLIP_PAGE_SIZE + 1,
                    )
                )
            },
            mapValue = { page ->
                require(
                    validatePayslipSelection(selection) is AppResult.Success &&
                        selection.year != null
                )
                val raw = page.checkpoint
                if (raw == null) PayrollReplicaOverview(null, PayslipPage(emptyList(), null))
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        val checkpoint = decodePayrollCheckpoint(cipher, key, access, raw)
                        val tag = payrollReplicaTag(key, access, "year", selection.year.toString())
                        val documents = page.pages.single()
                        require(documents.size <= PAYSLIP_PAGE_SIZE + 1)
                        require(documents.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
                        val summaries =
                            documents.map { document ->
                                val summary =
                                    Json.decodeFromString<PayslipSummaryDto>(
                                            decodePayrollDocument(
                                                cipher,
                                                key,
                                                access,
                                                raw.handle.generation,
                                                document,
                                            )
                                        )
                                        .toSummary()
                                require(
                                    document.id == "summary/${summary.id}" && document.version == 0L
                                )
                                require(summary.month.year == selection.year)
                                require(
                                    document.sortKey ==
                                        payrollSummarySortKey(summary.month, summary.id)
                                )
                                require(document.tags == setOf(tag))
                                val after = payrollSummaryAfter(selection)
                                require(after == null || document.sortKey > after)
                                summary
                            }
                        val items = summaries.take(PAYSLIP_PAGE_SIZE)
                        val cursor =
                            if (summaries.size > PAYSLIP_PAGE_SIZE)
                                "${items.last().month}:${items.last().id}"
                            else null
                        PayrollReplicaOverview(checkpoint, PayslipPage(items, cursor))
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override fun observeDetails(access: PayrollAccess, id: String) =
        safeStorageFlow(
            observeIndexedRecords(access, "payslip", id, null, 3),
            mapValue = { page ->
                require(validatePayslipId(id) is AppResult.Success)
                val raw = page.checkpoint
                if (raw == null) PayrollReplicaDetails(null, null)
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        val checkpoint = decodePayrollCheckpoint(cipher, key, access, raw)
                        val documents = page.pages.single()
                        require(documents.size <= 2)
                        val tag = payrollReplicaTag(key, access, "payslip", id)
                        var slip: Payslip? = null
                        var payments: PayslipPayments? = null
                        for (document in documents) {
                            require(document.tags == setOf(tag) && document.sortKey == document.id)
                            val json =
                                decodePayrollDocument(
                                    cipher,
                                    key,
                                    access,
                                    raw.handle.generation,
                                    document,
                                )
                            when (document.id) {
                                "payslip/$id" -> {
                                    require(document.version == 0L)
                                    slip = Json.decodeFromString<PayslipDto>(json).toPayslip(id)
                                }
                                "payments/$id" -> {
                                    payments =
                                        Json.decodeFromString<PayslipPaymentsDto>(json)
                                            .toPayments(id)
                                    require(payments.version == document.version)
                                }
                                else -> error("Unexpected payslip record.")
                            }
                        }
                        PayrollReplicaDetails(
                            checkpoint,
                            slip?.let { PayslipView(it, requireNotNull(payments)) },
                        )
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    /** Storage querying only: a retained checkpoint establishes that its encryption key exists. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeIndexedRecords(
        access: PayrollAccess,
        kind: String,
        value: String,
        after: String?,
        limit: Int,
    ): Flow<ReplicaQueryPage> =
        replicas
            .observeCheckpoint(payrollReplicaScope(access), payrollReplicaPartition(access))
            .map { it != null }
            .distinctUntilChanged()
            .flatMapLatest { retained ->
                if (!retained) flowOf(ReplicaQueryPage(null, listOf(emptyList())))
                else
                    flow {
                        val key = Base64.decode(requireNotNull(credentials.read()))
                        val tag =
                            try {
                                payrollReplicaTag(key, access, kind, value)
                            } finally {
                                key.fill(0)
                            }
                        emitAll(
                            replicas.observeQueries(
                                payrollReplicaScope(access),
                                payrollReplicaPartition(access),
                                listOf(ReplicaQuery(tag, after, limit)),
                            )
                        )
                    }
            }

    override suspend fun checkpoint(access: PayrollAccess) = safeStorageCall {
        val raw =
            replicas.checkpoint(payrollReplicaScope(access), payrollReplicaPartition(access))
                ?: return@safeStorageCall null
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            decodePayrollCheckpoint(cipher, key, access, raw)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun begin(checkpoint: PayrollCheckpoint, expectedGeneration: String?) =
        safeStorageCall {
            currentCoroutineContext().ensureActive()
            require(checkpoint.revision == 0L && !checkpoint.published)
            val key =
                keyCreation.withLock {
                    readOrCreatePayrollReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodePayrollCheckpoint(cipher, key, checkpoint)
                currentCoroutineContext().ensureActive()
                replicas.begin(
                    payrollReplicaScope(checkpoint.access),
                    payrollReplicaPartition(checkpoint.access),
                    checkpoint.generation,
                    sealed,
                    expectedGeneration,
                )
            } finally {
                key.fill(0)
            }
        }

    override suspend fun commit(
        previous: PayrollCheckpoint,
        next: PayrollCheckpoint,
        entries: List<PayrollReplicaEntry>,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(next.generation == previous.generation && next.revision == previous.revision + 1)
        require(next.access == previous.access && (!previous.published || next.published))
        val access = previous.access
        val batch = entries.toList()
        require(
            batch.size <= 16 &&
                batch.distinctBy { it.reference.collection to it.reference.id }.size == batch.size
        )
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            val documents = mutableListOf<ReplicaDocument>()
            for (entry in batch) {
                documents += encodePayrollReplicaEntry(cipher, key, next, entry)
            }
            val sealed = encodePayrollCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            replicas.commit(
                ReplicaHandle(
                    payrollReplicaScope(access),
                    payrollReplicaPartition(access),
                    previous.generation,
                ),
                previous.revision,
                sealed,
                documents.toList(),
                next.published,
            )
        } finally {
            key.fill(0)
        }
    }

    override suspend fun clear(access: PayrollAccess) = safeStorageCall {
        // Confirmed denial also removes the preceding last-view migration fallback.
        for (view in listOf("overview", "details")) {
            if (!replicas.clear(ReplicaScope(access.sessionId), payslipPartition(access, view)))
                return@safeStorageCall false
        }
        currentCoroutineContext().ensureActive()
        replicas.clear(payrollReplicaScope(access), payrollReplicaPartition(access))
    }
}
