package dev.fajar.starter.workspace.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CompanyPolicyDto(
    val schemaVersion: Int,
    val version: Long?,
    val enabledModules: List<String>,
    val minimumBuilds: CompanyMinimumBuildsDto,
    val maintenance: CompanyMaintenanceDto?,
    val maintenanceActive: Boolean,
    val serverTime: String,
    val validUntil: String,
)
