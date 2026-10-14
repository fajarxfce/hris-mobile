package dev.fajar.starter.people.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.data.api.PeopleApi
import dev.fajar.starter.people.data.replica.datasources.*
import dev.fajar.starter.people.data.replica.mappers.toReplicaDto
import dev.fajar.starter.people.data.replica.repositories.RemoteDirectoryRecordRepository
import dev.fajar.starter.people.domain.replica.entities.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class DirectoryRecordRepositoryTest {
    private val access =
        DirectoryAccess(
            "session",
            directoryFixtureId(9001),
            "https://hris.example.test/",
            directoryFixtureId(9002),
            "Asia/Jakarta",
            setOf("people.directory.read"),
        )

    @Test
    fun canonicalResponseContainsOnlyDirectoryFieldsAndUsesItsOwnRoute() = runTest {
        val record =
            directoryFixtureRecord(1).let {
                it.copy(employee = it.employee.copy(name = "部".repeat(200)))
            }
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(
                        "/api/v1/companies/${access.companyId}/employees/directory/${record.employee.id}",
                        request.url.encodedPath,
                    )
                    respond(
                        Json.encodeToString(record.toReplicaDto()),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            assertEquals(
                AppResult.Success(record),
                RemoteDirectoryRecordRepository(ApiDirectoryRecordDataSource(PeopleApi(client)))
                    .record(access, record.employee.id),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun aWithdrawnRecordPreservesItsMachineCodeWithoutPrivateErrorText() = runTest {
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        """{"code":"directory_employee_not_found","title":"Private diagnostic"}""",
                        HttpStatusCode.NotFound,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val result =
                assertIs<AppResult.Failed>(
                    RemoteDirectoryRecordRepository(ApiDirectoryRecordDataSource(PeopleApi(client)))
                        .record(access, directoryFixtureId(1))
                )
            assertEquals("directory_employee_not_found", result.failure.code)
            assertFalse(result.failure.message.contains("Private diagnostic"))
        } finally {
            client.close()
        }
    }

    @Test
    fun mismatchedIdsVersionsAndIncompleteAssignmentPairsFailInsideTheBoundary() = runTest {
        val original = directoryFixtureRecord(1).toReplicaDto()
        for (invalid in
            listOf(
                original.copy(id = directoryFixtureId(2)),
                original.copy(version = -1),
                original.copy(departmentId = null),
                original.copy(name = " "),
            )) {
            val repository =
                RemoteDirectoryRecordRepository(
                    object : DirectoryRecordDataSource {
                        override suspend fun record(
                            sessionId: String,
                            companyId: String,
                            id: String,
                        ) = invalid
                    }
                )
            assertIs<AppResult.Failed>(repository.record(access, original.id))
        }
    }

    @Test
    fun invalidPathsNeverReachTheSourceAndCancellationPropagates() = runTest {
        var reads = 0
        val repository =
            RemoteDirectoryRecordRepository(
                object : DirectoryRecordDataSource {
                    override suspend fun record(
                        sessionId: String,
                        companyId: String,
                        id: String,
                    ): dev.fajar.starter.people.data.replica.dto.DirectoryRecordDto {
                        reads++
                        throw CancellationException("Stopped")
                    }
                }
            )
        assertIs<AppResult.Failed>(repository.record(access, "../profile"))
        assertEquals(0, reads)
        assertFailsWith<CancellationException> { repository.record(access, directoryFixtureId(1)) }
    }
}
