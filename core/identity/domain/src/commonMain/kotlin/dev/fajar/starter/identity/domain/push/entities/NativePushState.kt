package dev.fajar.starter.identity.domain.push.entities

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
) {
    override fun toString() = "NativePushState([redacted])"
}
