package dev.fajar.starter.workspace.data

import dev.fajar.starter.workspace.data.dto.*
import dev.fajar.starter.workspace.data.mappers.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import kotlin.test.*

class CompanyPolicyMappingTest {
    private val dto =
        CompanyPolicyDto(
            1,
            null,
            listOf("PEOPLE", "COMMUNICATIONS"),
            CompanyMinimumBuildsDto(1, 0, 2),
            null,
            false,
            "2026-10-11T00:00:00Z",
            "2026-10-11T00:01:00Z",
        )

    @Test
    fun additiveModuleKeysNeverEnableAnUnknownClientCapabilityAndKnownPolicyRoundTrips() {
        val policy = dto.copy(enabledModules = dto.enabledModules + "FUTURE_MODULE").toPolicy()
        assertEquals(
            setOf(CompanyModule.PEOPLE, CompanyModule.COMMUNICATIONS),
            policy.enabledModules,
        )
        assertEquals(policy, policy.toDto().toPolicy())
    }

    @Test
    fun firstAndLastServerRevisionsAreAcceptedWithoutConvertingTheDefaultVersion() {
        listOf(null, 0L, 9999L).forEach { version ->
            assertEquals(version, dto.copy(version = version).toPolicy().version)
        }
    }

    @Test
    fun invalidFreshnessBuildsSchemaAndMaintenanceAreRejectedAtTheDataBoundary() {
        val invalid =
            listOf(
                dto.copy(schemaVersion = 2),
                dto.copy(version = -1),
                dto.copy(version = 10000),
                dto.copy(enabledModules = listOf("PEOPLE", "PEOPLE")),
                dto.copy(validUntil = dto.serverTime),
                dto.copy(validUntil = "2026-10-11T00:01:01Z"),
                dto.copy(minimumBuilds = dto.minimumBuilds.copy(android = -1)),
                dto.copy(minimumBuilds = dto.minimumBuilds.copy(ios = 1_000_000_000)),
                dto.copy(maintenanceActive = true),
                dto.copy(
                    maintenance = CompanyMaintenanceDto(dto.serverTime, "2026-10-19T00:00:00Z"),
                    maintenanceActive = true,
                ),
                dto.copy(
                    maintenance = CompanyMaintenanceDto(dto.serverTime, "2026-10-11T01:00:00Z"),
                    maintenanceActive = false,
                ),
            )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { it.toPolicy() } }
    }
}
