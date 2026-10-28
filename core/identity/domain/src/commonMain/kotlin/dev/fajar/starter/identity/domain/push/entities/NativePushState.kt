package dev.fajar.starter.identity.domain.push.entities

import kotlin.time.Instant

/** Installation-owned secure state, independent of the active account's disposable cache. */
data class NativePushState(
    val desired: Boolean = false,
    val owner: NativePushOwner? = null,
    val token: String? = null,
    val registration: NativePushRegistration? = null,
    val pending: NativePushCommand? = null,
    val retiringToken: Boolean = false,
    val revocations: List<NativePushOwner> = emptyList(),
    val enableRequested: Boolean = false,
    val automaticAttempts: Int = 0,
    val automaticRetryAfter: Instant? = null,
) {
    override fun toString() = "NativePushState([redacted])"
}
