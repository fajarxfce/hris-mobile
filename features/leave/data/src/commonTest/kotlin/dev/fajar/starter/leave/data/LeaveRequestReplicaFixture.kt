package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.EncryptedLeaveReplicaRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.initialLeaveCheckpoint
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.datetime.*

class LeaveRequestReplicaFixture(val db: AppDatabase) {
    val raw = LeaveDataFixture()
    val access = raw.access
    val keys = MemoryCredentialStore()
    val legacyKeys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    val repository = EncryptedLeaveReplicaRepository(db.replicas, cipher, keys, legacyKeys)
    private var acquisition = 0

    suspend fun activate() =
        db.accounts.activate(access.sessionId, leaveReplicaScope(access).accountKey)

    fun snapshot(request: LeaveRequestDetails, owner: LeaveAccess = access) =
        LeaveRequestSnapshot(
            owner,
            "acquisition-${++acquisition}",
            LeaveRequestSelection(request.id),
            request,
            raw.now,
            raw.now + 1.hours,
        )

    suspend fun checkpoint(): LeaveCheckpoint? =
        assertIs<AppResult.Success<LeaveCheckpoint?>>(repository.checkpoint(access)).value

    suspend fun writeCheckpoint(owner: LeaveAccess = access): String? =
        assertIs<AppResult.Success<String?>>(repository.requestCheckpoint(owner)).value

    suspend fun fresh(snapshot: LeaveRequestSnapshot, history: LeaveHistoryPage? = null) {
        assertEquals(
            AppResult.Success(true),
            repository.replaceRequest(
                snapshot.access,
                snapshot.request.id,
                writeCheckpoint(snapshot.access),
                snapshot,
                history,
            ),
        )
    }

    suspend fun request(
        id: String = raw.request.id,
        after: Long? = null,
        owner: LeaveAccess = access,
    ): LeaveRequestSnapshot? =
        assertIs<AppResult.Success<LeaveRequestSnapshot?>>(
                repository.observeRequest(owner, LeaveRequestSelection(id, after)).first()
            )
            .value

    suspend fun publish(requests: List<LeaveRequestDetails>, publish: Boolean = true) {
        var current = checkpoint()
        if (current == null) {
            current = initialLeaveCheckpoint(access, "feed", raw.now)
            assertEquals(AppResult.Success(true), repository.begin(current, null))
        }
        for (batch in requests.chunked(8).ifEmpty { listOf(emptyList()) }) {
            val previous = requireNotNull(current)
            val next =
                previous.copy(
                    revision = previous.revision + 1,
                    phase = LeaveSyncPhase.CHANGES,
                    cursor = "head",
                    published = publish,
                    refreshedAt = if (publish) raw.now else null,
                )
            assertEquals(
                AppResult.Success(true),
                repository.commit(
                    previous,
                    next,
                    raw.directory.toDirectory(access, null),
                    batch.map {
                        LeaveReplicaEntry(
                            SyncReference(SyncCollection.LEAVE_REQUESTS, it.id, it.version),
                            LeaveReplicaRecord.Request(it),
                            raw.now,
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

fun replicaRequestId(index: Int) = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"

fun LeaveDataFixture.requestFacts(
    id: String = request.id,
    version: Long = 0,
    dense: Boolean = false,
): LeaveRequestDetails {
    val dates =
        if (!dense) detail.days
        else
            (0..365).map { index ->
                val date = LocalDate(2026, 1, 1).plus(index, DateTimeUnit.DAY).toString()
                detail.days
                    .first()
                    .copy(
                        workDate = date,
                        startsAt = "${date}T01:00:00Z",
                        endsAt = "${date}T10:00:00Z",
                    )
            }
    return detail
        .copy(
            id = id,
            version = version,
            days = dates,
            chargedDays = if (dense) "366" else detail.chargedDays,
            reason = if (dense) "字".repeat(1000) else detail.reason,
            history = historyPage(version, null, dense).toHistoryDto(),
        )
        .toDetails(LeaveRequestSelection(id))
}

fun LeaveDataFixture.historyPage(
    version: Long,
    after: Long?,
    dense: Boolean = false,
): LeavePage<LeaveChange> {
    val first = after?.minus(1) ?: version
    val revisions = (first downTo maxOf(0, first - 9)).toList()
    return LeavePage(
        revisions.map {
            LeaveChange(
                it,
                LeaveChangeKind.DECIDED,
                LeaveStatus.PENDING,
                now + it.seconds,
                if (dense) "字".repeat(1000) else "Reviewed",
            )
        },
        revisions.lastOrNull()?.takeIf { revisions.size == 10 && it > 0 }?.toString(),
    )
}
