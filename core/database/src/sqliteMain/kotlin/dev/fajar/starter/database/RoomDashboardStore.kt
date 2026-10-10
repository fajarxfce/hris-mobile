package dev.fajar.starter.database

import dev.fajar.starter.database.dao.DashboardDao
import dev.fajar.starter.database.entities.*
import kotlinx.coroutines.flow.map

internal class RoomDashboardStore(private val dao: DashboardDao) : DashboardStore {
    override fun observe(sessionId: String) =
        dao.observe(sessionId).map { rows ->
            rows.firstOrNull()?.let { first ->
                DashboardRecord(
                    first.projects,
                    first.active,
                    first.members,
                    rows.mapNotNull { row ->
                        row.activityId?.let { id ->
                            ActivityRecord(
                                id,
                                requireNotNull(row.title),
                                requireNotNull(row.detail),
                                requireNotNull(row.time),
                                row.saved,
                            )
                        }
                    },
                    first.updatedAtEpochMillis,
                    first.pendingChanges,
                    first.snapshot,
                    first.nextCursor,
                )
            }
        }

    override suspend fun replaceContent(sessionId: String, record: DashboardRecord) =
        dao.replaceContent(
            sessionId,
            DashboardEntity(
                projects = record.projects,
                active = record.active,
                members = record.members,
                updatedAtEpochMillis = record.updatedAtEpochMillis,
                snapshot = record.snapshot,
                nextCursor = record.nextCursor,
            ),
            record.activity.mapIndexed { index, row ->
                ActivityEntity(row.id, row.title, row.detail, row.time, index)
            },
        )

    override suspend fun appendPage(
        sessionId: String,
        snapshot: String,
        cursor: String,
        page: List<ActivityRecord>,
        nextCursor: String?,
    ) =
        dao.appendPage(
            sessionId,
            snapshot,
            cursor,
            page.map { ActivityEntity(it.id, it.title, it.detail, it.time, 0) },
            nextCursor,
        )

    override suspend fun setSaved(sessionId: String, change: ActivityChangeRecord) =
        dao.setSaved(
            sessionId,
            ActivityChangeEntity(
                operationId = change.operationId,
                activityId = change.activityId,
                saved = change.saved,
            ),
        )

    override suspend fun pendingChanges(sessionId: String, limit: Int) =
        dao.pendingChanges(sessionId, limit).map {
            ActivityChangeRecord(it.operationId, it.activityId, it.saved)
        }

    override suspend fun acknowledge(sessionId: String, operationId: String) =
        dao.acknowledge(sessionId, operationId)
}
