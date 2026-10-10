package dev.fajar.starter.database

import kotlinx.coroutines.flow.Flow

/** Raw storage operations. Local preference changes and outbox inserts commit together. */
interface DashboardStore {
    fun observe(sessionId: String): Flow<DashboardRecord?>

    /** Remote content never overwrites device-owned saved preferences. */
    suspend fun replaceContent(sessionId: String, record: DashboardRecord): Boolean

    /**
     * Appends only while the account, snapshot and cursor still match. Duplicate IDs are retained
     * once.
     */
    suspend fun appendPage(
        sessionId: String,
        snapshot: String,
        cursor: String,
        page: List<ActivityRecord>,
        nextCursor: String?,
    ): Boolean

    /** False means the activity does not exist; unchanged values do not enqueue another command. */
    suspend fun setSaved(sessionId: String, change: ActivityChangeRecord): Boolean

    suspend fun pendingChanges(sessionId: String, limit: Int): List<ActivityChangeRecord>

    /** Removes only this acknowledged operation, never a newer change for the same activity. */
    suspend fun acknowledge(sessionId: String, operationId: String)
}
