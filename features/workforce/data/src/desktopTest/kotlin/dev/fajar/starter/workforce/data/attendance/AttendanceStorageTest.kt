package dev.fajar.starter.workforce.data.attendance

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.location.domain.entities.LocationFix
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.workforce.data.WorkforceDataFixture
import dev.fajar.starter.workforce.data.attendance.mappers.*
import dev.fajar.starter.workforce.data.attendance.repositories.*
import dev.fajar.starter.workforce.data.mappers.toDirectory
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.*

class AttendanceStorageTest {
    private val f = WorkforceDataFixture()
    private val command =
        AttendanceCommand(
            "66666666-6666-4666-8666-666666666666",
            f.access.accountId,
            f.access.audience,
            f.access.companyId,
            f.employee.id,
            f.period.from,
            AttendanceKind.CLOCK_IN,
            f.now,
            "77777777-7777-4777-8777-777777777777",
            null,
            true,
            LocationFix(1.5, 2.5, 3.0, f.now, false),
        )

    @Test
    fun encryptedEvidenceSurvivesReopeningAndLostResponsesWithoutCrossAccountExposure() = runTest {
        val directory = Files.createTempDirectory("attendance-commands").toFile()
        var db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        val cipher = AesGcmContentCipher()
        try {
            db.accounts.activate(
                f.access.sessionId,
                accountStorageKey(f.access.audience, f.access.accountId),
            )
            var repository = EncryptedAttendanceCommandRepository(db.commands, cipher, keys)
            assertEquals(
                AppResult.Success(AttendanceCommandAdmission.STORED),
                repository.insert(f.access, command),
            )
            assertEquals(
                AppResult.Success(AttendanceCommandAdmission.OCCUPIED),
                repository.insert(
                    f.access,
                    command.copy(id = "88888888-8888-4888-8888-888888888888"),
                ),
            )
            val saved =
                assertIs<AppResult.Success<AttendanceCommandRecord?>>(
                        repository.find(f.access, command.id)
                    )
                    .value!!
            assertEquals(
                AppResult.Success(true),
                repository.update(f.access, saved, AttendanceCommandOutcome.Unconfirmed(), 1),
            )
            assertEquals(AppResult.Success(false), repository.remove(f.access, saved))
            val raw =
                db.commands.get(
                    attendanceCommandScope(f.access),
                    attendanceCommandPartition(f.access),
                    command.id,
                )!!
            assertFalse(raw.request.contains("CLOCK_IN"))
            assertFalse(raw.request.contains("latitude"))
            assertFalse(raw.state.contains("UNCONFIRMED"))
            db.close()
            db = createAppDatabase(directory)
            val replacement = f.access.copy(sessionId = "replacement")
            db.accounts.activate(
                replacement.sessionId,
                accountStorageKey(replacement.audience, replacement.accountId),
            )
            repository = EncryptedAttendanceCommandRepository(db.commands, cipher, keys)
            val restored =
                assertIs<AppResult.Success<AttendanceCommandRecord?>>(
                        repository.find(replacement, command.id)
                    )
                    .value!!
            assertEquals(command, restored.command)
            assertIs<AttendanceCommandOutcome.Unconfirmed>(restored.outcome)
            assertEquals(AppResult.Success(false), repository.remove(replacement, saved))
            assertEquals(
                AppResult.Success(true),
                repository.update(replacement, restored, AttendanceCommandOutcome.Confirmed(0)),
            )
            db.accounts.activate(
                "foreign",
                accountStorageKey(replacement.audience, "99999999-9999-4999-8999-999999999999"),
            )
            assertNull(
                assertIs<AppResult.Success<AttendanceCommandRecord?>>(
                        repository.find(replacement, command.id)
                    )
                    .value
            )
        } finally {
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aMissingContentKeyCannotReplaceExistingCommandEvidence() = runTest {
        val directory = Files.createTempDirectory("attendance-lost-key").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(
                f.access.sessionId,
                accountStorageKey(f.access.audience, f.access.accountId),
            )
            val repository =
                EncryptedAttendanceCommandRepository(db.commands, AesGcmContentCipher(), keys)
            assertEquals(
                AppResult.Success(AttendanceCommandAdmission.STORED),
                repository.insert(f.access, command),
            )
            keys.write(null)
            assertIs<AppResult.Failed>(
                repository.insert(
                    f.access,
                    command.copy(
                        id = "88888888-8888-4888-8888-888888888888",
                        kind = AttendanceKind.CLOCK_OUT,
                    ),
                )
            )
            assertNull(keys.read())
            assertTrue(db.commands.containsRecords())
        } finally {
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun attendanceSnapshotUsesAnAdmittedWorkspaceKeyAndIsAtomicAndScoped() = runTest {
        val directory = Files.createTempDirectory("attendance-cache").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        val cipher = AesGcmContentCipher()
        try {
            db.accounts.activate(f.access.sessionId)
            val period =
                CalendarPeriod(
                    f.period.from.minus(5, DateTimeUnit.DAY),
                    f.period.from.plus(1, DateTimeUnit.DAY),
                )
            val snapshot =
                AttendanceSnapshot(
                    f.access,
                    "generation",
                    f.directory.toDirectory(f.access, null),
                    f.employee.id,
                    period,
                    WorkCalendar(
                        null,
                        List(7) { WorkDay.Unassigned(period.from.plus(it, DateTimeUnit.DAY)) },
                    ),
                    List(7) {
                        AttendanceDay(
                            period.from.plus(it, DateTimeUnit.DAY),
                            emptyList(),
                            0,
                            0,
                            false,
                            null,
                        )
                    },
                    f.now,
                    f.access.authorizationExpiresAt,
                )
            val repository = EncryptedAttendanceCacheRepository(db.replicas, cipher, keys)
            assertIs<AppResult.Failed>(repository.replace(f.access, null, snapshot))
            keys.writeIfAbsent(Base64.encode(cipher.generateKey()))
            assertEquals(AppResult.Success(true), repository.replace(f.access, null, snapshot))
            assertEquals(
                snapshot,
                assertIs<AppResult.Success<AttendanceSnapshot?>>(
                        repository.observe(f.access).first()
                    )
                    .value,
            )
            assertEquals(AppResult.Success(false), repository.replace(f.access, "stale", null))
            val foreign = f.access.copy(companyId = "99999999-9999-4999-8999-999999999999")
            assertNull(
                assertIs<AppResult.Success<AttendanceSnapshot?>>(
                        repository.observe(foreign).first()
                    )
                    .value
            )
            assertEquals(
                AppResult.Success(true),
                repository.replace(f.access, snapshot.generation, null),
            )
            assertNull(
                assertIs<AppResult.Success<AttendanceSnapshot?>>(
                        repository.observe(f.access).first()
                    )
                    .value
            )
        } finally {
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }
}
