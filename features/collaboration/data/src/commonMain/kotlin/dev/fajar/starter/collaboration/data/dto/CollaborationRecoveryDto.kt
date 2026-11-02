package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationDevicePageDto(
    val accountId: String,
    val nativeSessionId: String,
    val devices: List<CollaborationDeviceReferenceDto>,
    val nextAfterCompanyId: String?,
)

@Serializable
data class CollaborationDeviceReferenceDto(
    val companyId: String,
    val deviceId: String,
    val version: Long,
    val state: String,
    val platform: String,
)

@Serializable
data class CollaborationAdmissionDto(
    val companyId: String,
    val accountId: String,
    val deviceId: String,
    val deviceVersion: Long,
    val nativeSessionId: String,
    val nativeSessionVersion: Long,
    val credentialVersion: Long,
    val admittedAt: String,
    val expiresAt: String,
)
