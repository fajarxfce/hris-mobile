package dev.fajar.starter.documents.domain.entities

import kotlin.time.Instant

data class DocumentAccess(
    val sessionId: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val permissions: Set<String>,
    val sessionExpiresAt: Instant,
    val authorizationExpiresAt: Instant,
    val authorizationExpiryCode: String,
)
