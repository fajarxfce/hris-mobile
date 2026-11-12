package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** A bounded local metadata row may contain no locator while still advancing past internal data. */
fun captureCollaborationSpaceHistoryRecoveryPage(
    page: CollaborationAccountIncomingHistoryLocatorPage,
    after: String?,
): CollaborationAccountIncomingHistoryLocatorPage? {
    if (page.locators.size > 1) return null
    val next = page.next
    if (
        next != null &&
            (next.length !in 1..160 ||
                next.any { it !in '!'..'~' } ||
                (after != null && next <= after))
    )
        return null
    val locators = page.locators.toList()
    if (
        locators.any { locator ->
            val owner =
                when (locator) {
                    is CollaborationAccountIncomingHistoryLocator.Company -> locator.companyId
                    is CollaborationAccountIncomingHistoryLocator.Space -> locator.spaceId
                }
            !validCollaborationSpaceConversationId(owner) ||
                !validCollaborationSpaceConversationId(locator.history.conversationId) ||
                !validCollaborationSpaceConversationId(locator.history.deviceId)
        }
    )
        return null
    return page.copy(locators = locators)
}

/** A backwards wall clock must not strand an otherwise valid retained pass. */
fun eligibleCollaborationSpaceHistoryRecovery(
    value: CollaborationSpaceHistoryRecovery,
    now: Long,
): Boolean {
    if (
        now !in 0..9_007_199_254_440_991L ||
            !validCollaborationSpaceHistoryRecovery(value) ||
            value.complete
    )
        return false
    val retry = value.retry ?: return true
    return now < retry.attemptedAtEpochMillis || now >= retry.eligibleAtEpochMillis
}
