package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workforce.data.mappers.toDirectory
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.data.overtime.repositories.EncryptedOvertimeReplicaRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

class OvertimeReplicaFixture(val db: AppDatabase) {
    val raw = OvertimeDataFixture()
    val access = raw.access
    val keys = MemoryCredentialStore()
    val legacyKeys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    val repository = EncryptedOvertimeReplicaRepository(db.replicas, cipher, keys, legacyKeys)
    val directory = raw.base.directory.toDirectory(access, null)
    private var acquisition = 0

    suspend fun activate() =
        db.accounts.activate(access.sessionId, overtimeReplicaScope(access).accountKey)

    fun snapshot(details: OvertimeDetails, owner: WorkforceAccess = access) =
        OvertimeSnapshot(
            owner,
            "acquisition-${++acquisition}",
            OvertimeRequestView(
                OvertimeRequestSelection(details.request.id, details.request.employeeId),
                details,
            ),
            raw.base.now,
            raw.base.now + 1.hours,
        )

    suspend fun checkpoint(owner: WorkforceAccess = access): OvertimeCheckpoint? =
        assertIs<AppResult.Success<OvertimeCheckpoint?>>(repository.checkpoint(owner)).value

    suspend fun writeCheckpoint(owner: WorkforceAccess = access): String? =
        assertIs<AppResult.Success<String?>>(repository.requestCheckpoint(owner)).value

    suspend fun fresh(
        snapshot: OvertimeSnapshot<OvertimeRequestView>,
        history: OvertimeHistoryPage? = null,
    ) {
        assertEquals(
            AppResult.Success(true),
            repository.replaceRequest(
                snapshot.access,
                snapshot.value.selection.requestId,
                writeCheckpoint(snapshot.access),
                snapshot,
                history,
            ),
        )
    }

    suspend fun request(
        id: String = raw.request.id,
        after: Long? = null,
        owner: WorkforceAccess = access,
    ): OvertimeSnapshot<OvertimeRequestView>? =
        assertIs<AppResult.Success<OvertimeSnapshot<OvertimeRequestView>?>>(
                repository
                    .observeRequest(
                        owner,
                        OvertimeRequestSelection(id, raw.base.employee.id, historyAfter = after),
                    )
                    .first()
            )
            .value

    suspend fun publish(requests: List<OvertimeDetails>, publish: Boolean = true) {
        var current = checkpoint()
        if (current == null) {
            current = initialOvertimeCheckpoint(access, "feed", raw.base.now)
            assertEquals(AppResult.Success(true), repository.begin(current, null))
        }
        for (batch in requests.chunked(8).ifEmpty { listOf(emptyList()) }) {
            val previous = requireNotNull(current)
            val next =
                previous.copy(
                    revision = previous.revision + 1,
                    phase = OvertimeSyncPhase.CHANGES,
                    cursor = "head",
                    published = publish,
                    refreshedAt = if (publish) raw.base.now else null,
                )
            assertEquals(
                AppResult.Success(true),
                repository.commit(
                    previous,
                    next,
                    directory,
                    batch.map {
                        OvertimeReplicaEntry(
                            SyncReference(
                                SyncCollection.OVERTIME_REQUESTS,
                                it.request.id,
                                it.request.version,
                            ),
                            it,
                            raw.base.now,
                        )
                    },
                ),
            )
            current = next
        }
    }

    fun close() {
        db.close()
        keys.close()
        legacyKeys.close()
    }
}

fun overtimeReplicaId(index: Int) = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"

fun OvertimeDataFixture.facts(
    id: String = request.id,
    version: Long = 0,
    date: LocalDate = LocalDate(2026, 10, 12),
    dense: Boolean = false,
): OvertimeDetails {
    val base = detail.toDetails(selection).request
    val pending = version > 0
    val approvalId = "77777777-7777-7777-7777-777777777777".takeIf { pending }
    val request =
        base.copy(
            id = id,
            workDate = date,
            schedule = WorkDay.Off(date, CalendarOrigin.ROSTER, 0, null),
            reason = if (dense) "字".repeat(1000) else base.reason,
            version = version,
            status = if (pending) OvertimeStatus.PENDING else OvertimeStatus.PLANNED,
            actual = base.requested.takeIf { pending },
            submittedBy = base.authorId.takeIf { pending },
            submittedAt = base.createdAt.takeIf { pending },
            approvalId = approvalId,
        )
    return OvertimeDetails(
        request,
        approvalId?.let { OvertimeApproval(it, OvertimeApprovalStatus.PENDING, 0, 1, version) },
        history(request, null, dense),
        if (pending) setOf(OvertimeAction.WITHDRAW)
        else setOf(OvertimeAction.WITHDRAW, OvertimeAction.SUBMIT_ACTUAL),
    )
}

fun OvertimeDataFixture.history(
    request: OvertimeRequest,
    after: Long?,
    dense: Boolean = false,
): OvertimePage<OvertimeChange> {
    val first = after?.minus(1) ?: request.version
    val revisions = (first downTo maxOf(0, first - 9)).toList()
    return OvertimePage(
        revisions.map { revision ->
            OvertimeChange(
                revision,
                when (revision) {
                    0L -> OvertimeChangeKind.PLANNED
                    1L -> OvertimeChangeKind.ACTUAL_SUBMITTED
                    else -> OvertimeChangeKind.DECIDED
                },
                if (revision == 0L) OvertimeStatus.PLANNED else OvertimeStatus.PENDING,
                if (revision == 0L) null else request.actual,
                0,
                if (revision == 0L) null else request.approvalId,
                access.accountId,
                base.now + revision.seconds,
                if (dense) "字".repeat(1000) else "Reviewed",
            )
        },
        revisions.lastOrNull()?.takeIf { revisions.size == 10 && it > 0 }?.toString(),
    )
}
