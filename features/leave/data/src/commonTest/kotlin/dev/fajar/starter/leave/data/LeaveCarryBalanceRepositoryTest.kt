package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.data.datasources.LeaveRemoteDataSource
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.RemoteLeaveRepository
import dev.fajar.starter.leave.domain.entities.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

class LeaveCarryBalanceRepositoryTest {
    @Test
    fun directoryAndCanonicalReadsRetainTheSameBreakdownWithoutRecomputingTheUtcDeadline() =
        runTest {
            val f = LeaveDataFixture()
            val carry = carryBalanceDto().copy(expiresAt = "2026-03-31T18:00:00Z")
            val raw =
                f.balance(f.access.sessionId, f.access.companyId, f.balance.balance.accountId!!)
                    .copy(carry = carry)
            f.balances =
                f.balances.copy(
                    balances =
                        LeavePageDto(
                            listOf(f.balance.copy(balance = f.balance.balance.copy(carry = carry)))
                        )
                )
            val repository =
                RemoteLeaveRepository(
                    object : LeaveRemoteDataSource by f {
                        override suspend fun balance(
                            sessionId: String,
                            companyId: String,
                            id: String,
                        ) = raw
                    }
                )
            val canonical =
                assertIs<AppResult.Success<LeaveReplicaRecord.Balance>>(
                        repository.balance(f.access, raw.id)
                    )
                    .value
            val directory =
                assertIs<AppResult.Success<LeavePage<LeaveBalance>>>(
                        repository.balances(f.access, f.employee.id, 2026, null)
                    )
                    .value
            assertEquals(directory.items.single(), canonical.value)
            assertEquals(LeaveDays(21), canonical.value.available)
            val mapped = assertNotNull(canonical.value.carry)
            assertEquals(LeaveDays(5), mapped.available)
            assertEquals(LeaveDays(8), mapped.awarded)
            assertEquals(LeaveDays(1), mapped.expired)
            assertEquals(LocalDate(2026, 3, 31), mapped.validThrough)
            assertEquals(Instant.parse("2026-03-31T18:00:00Z"), mapped.expiresAt)
            assertEquals(
                carry,
                Json.decodeFromString<LeaveAccountDto>(Json.encodeToString(canonical.toDto())).carry,
            )
            val encoded = directory.toBalancesDto(f.employee.id, 2026)
            assertEquals(
                directory,
                Json.decodeFromString<LeaveBalancesDto>(Json.encodeToString(encoded))
                    .toBalances(f.employee.id, 2026, null),
            )
        }

    @Test
    fun legacyAndUnlimitedCarryRemainReadableWithoutInventingDates() = runTest {
        val f = LeaveDataFixture()
        val raw = f.balance(f.access.sessionId, f.access.companyId, f.balance.balance.accountId!!)
        val oldJson = Json.encodeToString(raw)
        assertFalse(oldJson.contains("carry"))
        assertNull(
            Json.decodeFromString<LeaveAccountDto>(oldJson).toReplicaBalance(raw.id).value.carry
        )
        val unlimited =
            raw.copy(carry = carryBalanceDto().copy(validThrough = null, expiresAt = null))
        val result = unlimited.toReplicaBalance(raw.id)
        assertNull(result.value.carry?.expiresAt)
        assertNull(result.value.carry?.validThrough)
        assertEquals(LeaveDays(5), result.value.carry?.available)
    }

    @Test
    fun malformedCarryFactsStayInsideTheHttpBoundary() = runTest {
        val f = LeaveDataFixture()
        val valid =
            f.balance(f.access.sessionId, f.access.companyId, f.balance.balance.accountId!!)
                .copy(carry = carryBalanceDto())
        val badCarry =
            listOf(
                    carryBalanceDto().copy(awardedDays = "3.5"),
                    carryBalanceDto().copy(availableDays = "1.25"),
                    carryBalanceDto().copy(availableDays = "-1"),
                    carryBalanceDto().copy(awardedDays = "367"),
                    carryBalanceDto().copy(version = -1),
                    carryBalanceDto().copy(validThrough = "2025-03-31"),
                    carryBalanceDto().copy(validThrough = "2026-03-30"),
                    carryBalanceDto().copy(validThrough = "2026-02-30"),
                    carryBalanceDto().copy(validThrough = null),
                    carryBalanceDto().copy(expiresAt = null),
                    carryBalanceDto().copy(expiresAt = "PRIVATE TECHNICAL DETAIL"),
                    carryBalanceDto().copy(timezone = "Invalid/Private"),
                    carryBalanceDto().copy(closingId = "invalid"),
                )
                .map { valid.copy(carry = it) } +
                listOf(
                    valid.copy(availableDays = "2"),
                    valid.copy(reservedDays = "0"),
                    valid.copy(consumedDays = "0"),
                    valid.copy(closed = true),
                )
        for (raw in badCarry) {
            val repository =
                RemoteLeaveRepository(
                    object : LeaveRemoteDataSource by f {
                        override suspend fun balance(
                            sessionId: String,
                            companyId: String,
                            id: String,
                        ) = raw
                    }
                )
            val failure = assertIs<AppResult.Failed>(repository.balance(f.access, valid.id)).failure
            assertEquals(FailureKind.Unexpected, failure.kind)
            assertFalse(failure.message.contains("PRIVATE"))
        }
    }
}
