package dev.fajar.starter.identity.domain.push.entities

import kotlin.time.Instant

enum class NativePushPlatform {
    ANDROID,
    IOS,
}

data class NativePushRegistration(
    val familyId: String,
    val platform: NativePushPlatform,
    val enabled: Boolean,
    val version: Long,
    val registeredAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString() = "NativePushRegistration([redacted])"
}

data class NativePushReceipt(val familyId: String, val version: Long)
