@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.data.mappers

import dev.fajar.starter.identity.data.dto.AccountSnapshotDto
import dev.fajar.starter.identity.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun AccountSnapshotDto.toAccount(): AccountSnapshot {
    require(companies.size <= 500 && permissions.size <= 1000)
    require(companies.map { it.id }.distinct().size == companies.size)
    val memberships =
        companies.map {
            Uuid.parse(it.id)
            require(
                it.name.isNotBlank() &&
                    it.name.length <= 200 &&
                    it.code.length in 1..64 &&
                    it.timezone.length in 1..100
            )
            CompanyMembership(it.id, it.name, it.code, it.timezone)
        }
    require(permissions.all { it.matches(Regex("[a-z][a-z0-9_.:-]{0,99}")) })
    val policy =
        SessionAssurance(
            assurance.required,
            assurance.verified,
            assurance.setupAvailable,
            assurance.validUntil?.let { Instant.parse(it).toEpochMilliseconds() },
            assurance.recentUntil?.let { Instant.parse(it).toEpochMilliseconds() },
        )
    return AccountSnapshot(
        account.toUser(),
        memberships,
        permissions.toSet(),
        policy,
        account.mfaConfigured,
    )
}
