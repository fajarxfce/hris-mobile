package dev.fajar.starter.security.domain.lock.entities

data class AppLockStatus(val enabled: Boolean, val sessionId: String?, val locked: Boolean)
