package dev.fajar.starter.workspace.domain.entities

import kotlin.time.Instant

data class CompanyPolicy(
    val version: Long?,
    val enabledModules: Set<CompanyModule>,
    val minimumBuilds: CompanyMinimumBuilds,
    val maintenance: CompanyMaintenance?,
    val maintenanceActive: Boolean,
    val serverTime: Instant,
    val validUntil: Instant,
)
