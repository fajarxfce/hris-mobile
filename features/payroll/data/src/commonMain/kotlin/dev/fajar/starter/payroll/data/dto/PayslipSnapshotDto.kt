package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipSnapshotDto<T>(
    val permissions: Set<String>,
    val sessionExpiresAt: String,
    val timezone: String,
    val fetchedAt: String,
    val expiresAt: String,
    val value: T,
)
