package dev.fajar.starter.collaboration.domain.entities

/** Account-owned coordinates retain their actual namespace; they grant no native authority. */
sealed interface CollaborationAccountIncomingHistoryLocator {
    val history: CollaborationIncomingHistoryLocator

    data class Company(
        val companyId: String,
        override val history: CollaborationIncomingHistoryLocator,
    ) : CollaborationAccountIncomingHistoryLocator {
        override fun toString() = "CollaborationAccountIncomingHistoryLocator.Company([redacted])"
    }

    data class Space(
        val spaceId: String,
        override val history: CollaborationIncomingHistoryLocator,
    ) : CollaborationAccountIncomingHistoryLocator {
        override fun toString() = "CollaborationAccountIncomingHistoryLocator.Space([redacted])"
    }
}

/** Other published metadata can advance an empty page; absence is not completion authority. */
data class CollaborationAccountIncomingHistoryLocatorPage(
    val locators: List<CollaborationAccountIncomingHistoryLocator>,
    val next: String?,
) {
    override fun toString() = "CollaborationAccountIncomingHistoryLocatorPage([redacted])"
}
