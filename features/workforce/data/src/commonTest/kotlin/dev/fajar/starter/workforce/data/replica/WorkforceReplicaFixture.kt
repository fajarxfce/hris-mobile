package dev.fajar.starter.workforce.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.AppDatabase
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.entities.SyncReference
import dev.fajar.starter.workforce.data.WorkforceDataFixture
import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.data.replica.datasources.MemoryWorkforceReadAdmissionDataSource
import dev.fajar.starter.workforce.data.replica.encoding.collection
import dev.fajar.starter.workforce.data.replica.mappers.workforceReplicaScope
import dev.fajar.starter.workforce.data.replica.repositories.EncryptedWorkforceReplicaRepository
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.replica.entities.*
import dev.fajar.starter.workforce.domain.replica.policies.initialWorkforceCheckpoint
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.*

class WorkforceReplicaFixture(val db: AppDatabase) {
    val raw = WorkforceDataFixture()
    val access = raw.access
    val directory = raw.directory.toDirectory(access, null)
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    val admissions = MemoryWorkforceReadAdmissionDataSource()
    val repository = EncryptedWorkforceReplicaRepository(db.replicas, cipher, keys, admissions)
    val shift = raw.shift.toShift()
    val schedule =
        WorkforceRecord.Schedule(
            workforceRecordId(1),
            2,
            raw.employee.id,
            LocalDate(2000, 1, 1),
            DayOfWeek.entries.associateWith { shift },
        )

    suspend fun activate() =
        db.accounts.activate(access.sessionId, workforceReplicaScope(access).accountKey)

    suspend fun checkpoint(owner: WorkforceAccess = access) =
        assertIs<AppResult.Success<WorkforceCheckpoint?>>(repository.checkpoint(owner)).value

    suspend fun publish(records: List<WorkforceRecord>, publish: Boolean = true) {
        var checkpoint = checkpoint()
        if (checkpoint == null) {
            checkpoint = initialWorkforceCheckpoint(access, "feed", raw.now)
            assertEquals(AppResult.Success(true), repository.begin(checkpoint, null))
        }
        for (batch in records.chunked(32).ifEmpty { listOf(emptyList()) }) {
            val previous = requireNotNull(checkpoint)
            val next =
                previous.copy(
                    revision = previous.revision + 1,
                    phase = WorkforceSyncPhase.CHANGES,
                    cursor = "head",
                    published = publish,
                    refreshedAt = if (publish) raw.now else null,
                )
            assertEquals(
                AppResult.Success(true),
                repository.commit(
                    previous,
                    next,
                    directory,
                    batch.map {
                        WorkforceReplicaEntry(SyncReference(it.collection(), it.id, it.version), it)
                    },
                ),
            )
            checkpoint = next
        }
    }

    suspend fun facts(
        period: CalendarPeriod = raw.period,
        employeeId: String = raw.employee.id,
        owner: WorkforceAccess = access,
    ) =
        assertIs<AppResult.Success<WorkforceReplicaFacts>>(
                repository.observeFacts(owner, employeeId, period).first()
            )
            .value

    fun attendance(index: Int, date: LocalDate) =
        WorkforceRecord.Attendance(
            workforceRecordId(1000 + index),
            0,
            raw.employee.id,
            AttendanceDay(
                date,
                emptyList(),
                0,
                0,
                false,
                AttendanceCorrection(workforceRecordId(2000 + index), null, null, 0),
            ),
        )

    fun close() {
        db.close()
        keys.close()
    }
}

fun workforceRecordId(index: Int) = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
