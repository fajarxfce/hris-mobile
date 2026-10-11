package dev.fajar.starter.workspace.data.mappers

import dev.fajar.starter.workspace.data.dto.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun CompanyPolicyDto.toPolicy(): CompanyPolicy {
    require(schemaVersion == 1 && (version == null || version in 0..9999))
    require(enabledModules.size <= 128 && enabledModules.distinct().size == enabledModules.size)
    require(enabledModules.all { it.matches(Regex("[A-Z][A-Z_]{0,63}")) })
    require(
        listOf(minimumBuilds.android, minimumBuilds.ios, minimumBuilds.web).all {
            it in 0..999_999_999
        }
    )
    val evaluated = Instant.parse(serverTime)
    val valid = Instant.parse(validUntil)
    require(valid > evaluated && valid - evaluated <= 60.seconds)
    val window =
        maintenance?.let {
            CompanyMaintenance(Instant.parse(it.startsAt), Instant.parse(it.endsAt)).also { range ->
                require(range.endsAt > range.startsAt && range.endsAt - range.startsAt <= 7.days)
            }
        }
    require(
        maintenanceActive ==
            (window != null && evaluated >= window.startsAt && evaluated < window.endsAt)
    )
    return CompanyPolicy(
        version,
        enabledModules
            .mapNotNull { name -> CompanyModule.entries.firstOrNull { it.name == name } }
            .toSet(),
        CompanyMinimumBuilds(minimumBuilds.android, minimumBuilds.ios, minimumBuilds.web),
        window,
        maintenanceActive,
        evaluated,
        valid,
    )
}

fun CompanyPolicy.toDto() =
    CompanyPolicyDto(
        1,
        version,
        enabledModules.map { it.name }.sorted(),
        CompanyMinimumBuildsDto(minimumBuilds.android, minimumBuilds.ios, minimumBuilds.web),
        maintenance?.let { CompanyMaintenanceDto(it.startsAt.toString(), it.endsAt.toString()) },
        maintenanceActive,
        serverTime.toString(),
        validUntil.toString(),
    )

fun CompanyPolicyRecord.toSnapshot() =
    CompanyPolicySnapshot(policy.toPolicy(), Instant.parse(requestedAt))

fun CompanyPolicySnapshot.toRecord() = CompanyPolicyRecord(policy.toDto(), requestedAt.toString())
