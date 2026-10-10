package dev.fajar.starter.database.dao

import androidx.room.*
import dev.fajar.starter.database.dto.DashboardRow
import dev.fajar.starter.database.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DashboardDao {
    @Query(
        """
        SELECT d.projects, d.active, d.members, d.updatedAtEpochMillis, d.snapshot, d.nextCursor,
            (SELECT COUNT(*) FROM dashboard_outbox) AS pendingChanges,
            a.id AS activityId, a.title, a.detail, a.time, COALESCE(p.saved, 0) AS saved
        FROM dashboard d LEFT JOIN dashboard_activity a ON 1 = 1
        LEFT JOIN activity_preferences p ON p.activityId = a.id
        WHERE d.id = 1 AND EXISTS(SELECT 1 FROM account_scope WHERE sessionId = :sessionId) ORDER BY a.position
    """
    )
    abstract fun observe(sessionId: String): Flow<List<DashboardRow>>

    @Query("SELECT EXISTS(SELECT 1 FROM account_scope WHERE sessionId = :sessionId)")
    protected abstract suspend fun ownsCache(sessionId: String): Boolean

    @Query("SELECT * FROM dashboard WHERE id = 1")
    protected abstract suspend fun dashboard(): DashboardEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM dashboard_activity")
    protected abstract suspend fun nextPosition(): Int

    @Transaction
    open suspend fun appendPage(
        sessionId: String,
        snapshot: String,
        cursor: String,
        rows: List<ActivityEntity>,
        nextCursor: String?,
    ): Boolean {
        if (!ownsCache(sessionId)) return false
        val current = dashboard() ?: return false
        if (current.snapshot != snapshot || current.nextCursor != cursor) return false
        var position = nextPosition()
        for (row in rows) {
            if (!containsActivity(row.id)) insertActivity(listOf(row.copy(position = position++)))
        }
        upsertDashboard(current.copy(nextCursor = nextCursor))
        return true
    }

    @Upsert protected abstract suspend fun upsertDashboard(row: DashboardEntity)

    @Insert protected abstract suspend fun insertActivity(rows: List<ActivityEntity>)

    @Query("DELETE FROM dashboard_activity") protected abstract suspend fun clearActivity()

    @Query("SELECT EXISTS(SELECT 1 FROM dashboard_activity WHERE id = :id)")
    protected abstract suspend fun containsActivity(id: String): Boolean

    @Query("SELECT saved FROM activity_preferences WHERE activityId = :id")
    protected abstract suspend fun saved(id: String): Boolean?

    @Upsert protected abstract suspend fun upsertPreference(row: ActivityPreferenceEntity)

    @Insert protected abstract suspend fun insertChange(row: ActivityChangeEntity)

    @Transaction
    open suspend fun replaceContent(
        sessionId: String,
        dashboard: DashboardEntity,
        activity: List<ActivityEntity>,
    ): Boolean {
        if (!ownsCache(sessionId)) return false
        upsertDashboard(dashboard)
        clearActivity()
        insertActivity(activity)
        return true
    }

    @Transaction
    open suspend fun setSaved(sessionId: String, change: ActivityChangeEntity): Boolean {
        if (!ownsCache(sessionId)) return false
        if (!containsActivity(change.activityId)) return false
        if ((saved(change.activityId) ?: false) == change.saved) return true
        upsertPreference(ActivityPreferenceEntity(change.activityId, change.saved))
        insertChange(change)
        return true
    }

    @Query(
        "SELECT * FROM dashboard_outbox WHERE EXISTS(SELECT 1 FROM account_scope WHERE sessionId = :sessionId) ORDER BY sequence LIMIT :limit"
    )
    abstract suspend fun pendingChanges(sessionId: String, limit: Int): List<ActivityChangeEntity>

    @Query(
        "DELETE FROM dashboard_outbox WHERE operationId = :operationId AND EXISTS(SELECT 1 FROM account_scope WHERE sessionId = :sessionId)"
    )
    abstract suspend fun acknowledge(sessionId: String, operationId: String)
}
