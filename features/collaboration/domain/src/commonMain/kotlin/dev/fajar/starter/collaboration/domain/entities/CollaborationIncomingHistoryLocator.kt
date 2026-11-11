package dev.fajar.starter.collaboration.domain.entities

/** Local logical coordinates only; metadata and authenticated history still own room identity. */
data class CollaborationIncomingHistoryLocator(val conversationId: String, val deviceId: String) {
    override fun toString() = "CollaborationIncomingHistoryLocator([redacted])"
}

/** A page may contain no incoming locators while advancing past other published history lanes. */
data class CollaborationIncomingHistoryLocatorPage(
    val locators: List<CollaborationIncomingHistoryLocator>,
    val next: String?,
) {
    override fun toString() = "CollaborationIncomingHistoryLocatorPage([redacted])"
}
