package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.data.api.LeaveApi
import dev.fajar.starter.leave.data.datasources.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.repositories.RemoteLeaveRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.network.RequestSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class LeaveRecordRepositoryTest {
    @Test
    fun canonicalHydrationUsesOwnedRequestAndBalanceRoutesWithoutListFilters() = runTest {
        val f = LeaveDataFixture()
        val account =
            f.balance(f.access.sessionId, f.access.companyId, f.balance.balance.accountId!!)
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    assertNull(request.url.parameters["employeeId"])
                    assertNull(request.url.parameters["year"])
                    assertNull(request.url.parameters["status"])
                    val body =
                        when (request.url.encodedPath) {
                            "/api/v1/companies/${f.access.companyId}/leave/me/requests/${f.request.id}" -> {
                                assertEquals("10", request.url.parameters["historyLimit"])
                                Json.encodeToString(f.detail)
                            }
                            "/api/v1/companies/${f.access.companyId}/leave/balances/${account.id}" ->
                                Json.encodeToString(account)
                            else -> error("Unexpected route")
                        }
                    respond(
                        body,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repository = RemoteLeaveRepository(ApiLeaveDataSource(LeaveApi(client)))
            val balance =
                assertIs<AppResult.Success<LeaveReplicaRecord.Balance>>(
                        repository.balance(f.access, account.id)
                    )
                    .value
            assertEquals("Annual leave", balance.value.typeName)
            assertEquals(f.employee.id, balance.employeeId)
            assertEquals(LeaveDays(21), balance.value.available)
            val request =
                assertIs<AppResult.Success<LeaveReplicaRecord.Request>>(
                        repository.request(f.access, f.request.id)
                    )
                    .value
            assertEquals(f.request.id, request.value.id)
            assertEquals(f.request.from, request.value.from.toString())
            assertEquals(f.request.until, request.value.until.toString())
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidCanonicalIdentityAmountsAndLabelsFailInsideTheHttpBoundary() = runTest {
        val f = LeaveDataFixture()
        val account =
            f.balance(f.access.sessionId, f.access.companyId, f.balance.balance.accountId!!)
        for (invalid in
            listOf(
                account.copy(id = f.employee.id),
                account.copy(availableDays = "1.25"),
                account.copy(typeName = "x".repeat(201)),
                account.copy(version = -1),
            )) {
            val repository =
                RemoteLeaveRepository(
                    object : LeaveRemoteDataSource by f {
                        override suspend fun balance(
                            sessionId: String,
                            companyId: String,
                            id: String,
                        ) = invalid
                    }
                )
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.balance(f.access, account.id)).failure.kind,
            )
        }
    }

    @Test
    fun cancellationDuringCanonicalAcquisitionRemainsCancellation() = runTest {
        val f = LeaveDataFixture()
        val entered = CompletableDeferred<Unit>()
        var closed = false
        val repository =
            RemoteLeaveRepository(
                object : LeaveRemoteDataSource by f {
                    override suspend fun balance(
                        sessionId: String,
                        companyId: String,
                        id: String,
                    ): LeaveAccountDto {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            closed = true
                        }
                    }
                }
            )
        val reading = async { repository.balance(f.access, f.balance.balance.accountId!!) }
        entered.await()
        reading.cancelAndJoin()
        assertTrue(reading.isCancelled)
        assertTrue(closed)
    }
}
