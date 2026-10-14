package dev.fajar.starter.people.data.profile

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.data.api.PeopleApi
import dev.fajar.starter.people.data.profile.datasources.*
import dev.fajar.starter.people.data.profile.dto.SelfProfileRecordDto
import dev.fajar.starter.people.data.profile.mappers.toReplicaDto
import dev.fajar.starter.people.data.profile.repositories.RemoteSelfProfileRecordRepository
import dev.fajar.starter.people.domain.entities.*
import dev.fajar.starter.people.domain.profile.entities.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class SelfProfileRecordRepositoryTest {
    private val access =
        SelfProfileAccess(
            "session",
            selfProfileFixtureId(9001),
            "https://hris.example.test/",
            selfProfileFixtureId(9002),
            "Asia/Jakarta",
            setOf("people.self.read"),
        )

    @Test
    fun aCoherentOwnedSnapshotUsesOneCanonicalRequestAndRetainsIndependentVersions() = runTest {
        val original = selfProfileFixtureRecord(1, version = 21)
        val assignment = EmploymentAssignment(selfProfileFixtureId(20), "ENG", "Engineering", true)
        val record =
            original.copy(
                profile =
                    original.profile.copy(
                        details =
                            original.profile.details.copy(
                                branch = assignment,
                                department = assignment.copy(id = selfProfileFixtureId(21)),
                                position = assignment.copy(id = selfProfileFixtureId(22)),
                                costCenter = assignment.copy(id = selfProfileFixtureId(23)),
                                manager =
                                    EmploymentManager(
                                        selfProfileFixtureId(24),
                                        "M01",
                                        "Manager",
                                        false,
                                    ),
                            ),
                        // The same person may be owned by another company in this installation.
                        person =
                            original.profile.person.copy(
                                ownerCompanyId = selfProfileFixtureId(9010)
                            ),
                    )
            )
        var calls = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(
                        "/api/v1/companies/${access.companyId}/employees/me/profiles/${record.id}",
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
            val result =
                RemoteSelfProfileRecordRepository(ApiSelfProfileRecordDataSource(PeopleApi(client)))
                    .record(access, record.id)
            assertEquals(AppResult.Success(record), result)
            assertEquals(1, calls)
            assertEquals(4, record.profile.details.employee.version)
            assertEquals(7, record.profile.person.version)
            assertNotEquals(
                record.profile.details.employee.startDate,
                record.profile.details.employee.effectiveFrom,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun withdrawnProfilePreservesItsMachineCodeWithoutPrivateErrorText() = runTest {
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        """{"code":"self_profile_not_found","title":"Private diagnostic"}""",
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
                    RemoteSelfProfileRecordRepository(
                            ApiSelfProfileRecordDataSource(PeopleApi(client))
                        )
                        .record(access, selfProfileFixtureId(1))
                )
            assertEquals("self_profile_not_found", result.failure.code)
            assertFalse(result.failure.message.contains("Private diagnostic"))
        } finally {
            client.close()
        }
    }

    @Test
    fun mismatchedOwnerIdentityVersionsAndUnresolvedAssignmentsFailInsideTheBoundary() = runTest {
        val original = selfProfileFixtureRecord(1).toReplicaDto()
        val employee = original.employment.employee
        for (invalid in
            listOf(
                original.copy(id = selfProfileFixtureId(2)),
                original.copy(version = -1),
                original.copy(timezone = "Invalid/Zone"),
                original.copy(
                    profile = original.profile.copy(accountId = selfProfileFixtureId(10))
                ),
                original.copy(profile = original.profile.copy(personId = selfProfileFixtureId(11))),
                original.copy(profile = original.profile.copy(legalName = "Someone else")),
                original.copy(profile = original.profile.copy(email = "other@example.test")),
                original.copy(
                    employment =
                        original.employment.copy(
                            employee = employee.copy(companyId = selfProfileFixtureId(12))
                        )
                ),
                original.copy(
                    employment =
                        original.employment.copy(
                            employee =
                                employee.copy(
                                    person =
                                        employee.person.copy(accountId = selfProfileFixtureId(13))
                                )
                        )
                ),
                original.copy(
                    employment =
                        original.employment.copy(
                            employee =
                                employee.copy(
                                    terms =
                                        employee.terms.copy(departmentId = selfProfileFixtureId(14))
                                )
                        )
                ),
                original.copy(profile = original.profile.copy(birthDate = "2026-02-30")),
            )) {
            val repository =
                RemoteSelfProfileRecordRepository(
                    object : SelfProfileRecordDataSource {
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
    fun oversizedCanonicalResponseIsRejectedBeforeDecoding() = runTest {
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        " ".repeat(65_537),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            assertIs<AppResult.Failed>(
                RemoteSelfProfileRecordRepository(ApiSelfProfileRecordDataSource(PeopleApi(client)))
                    .record(access, selfProfileFixtureId(1))
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidPathsNeverReachTheSourceAndCancellationPropagates() = runTest {
        var reads = 0
        val repository =
            RemoteSelfProfileRecordRepository(
                object : SelfProfileRecordDataSource {
                    override suspend fun record(
                        sessionId: String,
                        companyId: String,
                        id: String,
                    ): SelfProfileRecordDto {
                        reads++
                        throw CancellationException("Stopped")
                    }
                }
            )
        assertIs<AppResult.Failed>(repository.record(access, "../profile"))
        assertEquals(0, reads)
        assertFailsWith<CancellationException> {
            repository.record(access, selfProfileFixtureId(1))
        }
    }
}
