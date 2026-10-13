@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.data.attendance

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.workforce.data.WorkforceDataFixture
import dev.fajar.starter.workforce.data.attendance.mappers.*
import dev.fajar.starter.workforce.data.attendance.repositories.EncryptedAttendanceCacheRepository
import dev.fajar.starter.workforce.data.mappers.toDirectory
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

class AttendanceHistoryStorageTest {
    private val f = WorkforceDataFixture()
    private val cipher = AesGcmContentCipher()

    @Test
    fun aDenseMonthSurvivesRestartWithoutReplacingLegacyCaptureData() = runTest {
        val directory = Files.createTempDirectory("attendance-history").toFile()
        var db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(f.access.sessionId)
            val key = cipher.generateKey()
            keys.writeIfAbsent(Base64.encode(key))
            val recent = snapshot(AttendanceWindow.RECENT)
            val legacy =
                ReplicaSnapshot(
                    recent.generation,
                    Base64.encode(
                        cipher.encrypt(
                            key,
                            attendanceAssociatedData(f.access, recent.generation, "checkpoint"),
                            "v1".encodeToByteArray(),
                        )
                    ),
                    listOf(
                        ReplicaDocument(
                            "attendance",
                            0,
                            "attendance",
                            Base64.encode(
                                cipher.encrypt(
                                    key,
                                    attendanceAssociatedData(
                                        f.access,
                                        recent.generation,
                                        "attendance",
                                    ),
                                    Json.encodeToString(recent.toDto()).encodeToByteArray(),
                                )
                            ),
                        )
                    ),
                )
            assertTrue(
                db.replicas.replace(f.access.sessionId, attendancePartition(f.access), null, legacy)
            )
            key.fill(0)
            val month = snapshot(AttendanceWindow.MONTHLY)
            assertTrue(Json.encodeToString(month.toDto()).encodeToByteArray().size > 131_072)
            var repository = EncryptedAttendanceCacheRepository(db.replicas, cipher, keys)
            assertEquals(
                AppResult.Success(true),
                repository.replace(f.access, null, month, AttendanceWindow.MONTHLY),
            )
            assertEquals(AppResult.Success(recent), repository.observe(f.access).first())
            val raw =
                db.replicas
                    .observe(
                        f.access.sessionId,
                        attendancePartition(f.access, AttendanceWindow.MONTHLY),
                        limit = 33,
                    )
                    .first()
            assertEquals(32, raw.documents.size)
            assertTrue(
                raw.documents.all {
                    requireNotNull(it.payload).length <= 131_072 &&
                        it.payload?.contains("CLOCK_IN") == false
                }
            )
            db.close()
            db = createAppDatabase(directory)
            repository = EncryptedAttendanceCacheRepository(db.replicas, cipher, keys)
            assertEquals(
                AppResult.Success(month),
                repository.observe(f.access, AttendanceWindow.MONTHLY).first(),
            )
            assertEquals(AppResult.Success(recent), repository.observe(f.access).first())
            assertEquals(
                AppResult.Success(false),
                repository.replace(f.access, "stale", null, AttendanceWindow.MONTHLY),
            )
            assertEquals(
                AppResult.Success(null),
                repository
                    .observe(f.access.copy(companyId = "foreign"), AttendanceWindow.MONTHLY)
                    .first(),
            )
            assertIs<AppResult.Failed>(
                repository
                    .observe(f.access.copy(accountId = "foreign"), AttendanceWindow.MONTHLY)
                    .first()
            )
            val recentCheckpoint =
                db.replicas.checkpoint(f.access.sessionId, attendancePartition(f.access))
            assertTrue(
                db.replicas.replace(
                    f.access.sessionId,
                    attendancePartition(f.access),
                    recentCheckpoint,
                    ReplicaSnapshot(month.generation, raw.checkpoint!!.payload, raw.documents),
                )
            )
            assertIs<AppResult.Failed>(repository.observe(f.access).first())
            assertEquals(
                AppResult.Success(month),
                repository.observe(f.access, AttendanceWindow.MONTHLY).first(),
            )
        } finally {
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancellingPendingEncryptionCannotPublishOrRetainTheWorkspaceKey() = runTest {
        val directory = Files.createTempDirectory("attendance-history-cancel").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var borrowedKey: ByteArray? = null
        val pending =
            object : ContentCipher by cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray =
                    withContext(NonCancellable) {
                        borrowedKey = key
                        started.complete(Unit)
                        release.await()
                        cipher.encrypt(key, associatedData, plaintext)
                    }
            }
        try {
            db.accounts.activate(f.access.sessionId)
            keys.writeIfAbsent(Base64.encode(cipher.generateKey()))
            val repository = EncryptedAttendanceCacheRepository(db.replicas, pending, keys)
            val operation = launch {
                repository.replace(
                    f.access,
                    null,
                    snapshot(AttendanceWindow.MONTHLY),
                    AttendanceWindow.MONTHLY,
                )
            }
            started.await()
            operation.cancel()
            release.complete(Unit)
            operation.join()
            assertNull(
                db.replicas.checkpoint(
                    f.access.sessionId,
                    attendancePartition(f.access, AttendanceWindow.MONTHLY),
                )
            )
            assertTrue(requireNotNull(borrowedKey).all { it == 0.toByte() })
        } finally {
            release.complete(Unit)
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }

    private fun snapshot(window: AttendanceWindow): AttendanceSnapshot {
        val month = window == AttendanceWindow.MONTHLY
        val period =
            if (month) CalendarPeriod(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))
            else
                CalendarPeriod(
                    f.period.from.minus(5, DateTimeUnit.DAY),
                    f.period.from.plus(1, DateTimeUnit.DAY),
                )
        val days =
            List(period.from.daysUntil(period.until) + 1) { index ->
                val date = period.from.plus(index, DateTimeUnit.DAY)
                val recorded = date.atTime(9, 0).toInstant(TimeZone.UTC)
                val entries =
                    if (!month) emptyList()
                    else
                        List(16) { item ->
                            AttendanceEntry(
                                "70000000-0000-4000-8000-" +
                                    (index * 16 + item + 1).toString().padStart(12, '0'),
                                if (item % 2 == 0) AttendanceKind.CLOCK_IN
                                else AttendanceKind.CLOCK_OUT,
                                recorded,
                                recorded + 1.hours,
                                true,
                                AttendanceStatus.PENDING,
                                setOf("MISSING_LOCATION"),
                                WorkDay.Unassigned(date),
                                0,
                            )
                        }
                AttendanceDay(date, entries, 0, entries.size, false, null)
            }
        return AttendanceSnapshot(
            f.access,
            window.name.lowercase(),
            f.directory.toDirectory(f.access, null),
            f.employee.id,
            period,
            WorkCalendar(null, days.map { WorkDay.Unassigned(it.date) }),
            days,
            f.now,
            f.access.authorizationExpiresAt,
        )
    }
}
