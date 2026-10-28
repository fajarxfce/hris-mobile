package dev.fajar.starter.workspace.domain.entities

/** Local unfinished work, independent of sync freshness or scheduler activity. */
data class PendingActionSummary(
    val drafts: Int = 0,
    val queued: Int = 0,
    val unconfirmed: Int = 0,
    val needsReview: Int = 0,
    val transfers: Int = 0,
    val moreDrafts: Boolean = false,
) {
    val total: Int
        get() = drafts + queued + unconfirmed + needsReview + transfers

    init {
        require(listOf(drafts, queued, unconfirmed, needsReview, transfers).all { it >= 0 })
    }
}
