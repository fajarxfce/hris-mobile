@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.dashboard.data.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.dashboard.data.datasources.DashboardRemoteDataSource
import dev.fajar.starter.dashboard.data.dto.ActivityPreferenceRequest
import dev.fajar.starter.dashboard.data.mappers.*
import dev.fajar.starter.dashboard.domain.entities.ActivityChange
import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.storage.*
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single
class LocalFirstDashboardRepository(
    private val remote: DashboardRemoteDataSource,
    private val local: DashboardStore,
) : DashboardRepository {
    override fun observe(sessionId: String) =
        safeStorageFlow(
            local.observe(sessionId).map { it?.toDashboard()?.copy(sessionId = sessionId) }
        )

    override suspend fun refresh(sessionId: String): AppResult<Unit> =
        when (
            val response = safeApiCall {
                remote
                    .load(sessionId)
                    .toRecord(Clock.System.now().toEpochMilliseconds())
                    .copy(snapshot = Uuid.random().toString())
            }
        ) {
            is AppResult.Failed -> response
            is AppResult.Success ->
                safeStorageCall {
                    local.replaceContent(sessionId, response.value)
                    Unit
                }
        }

    override suspend fun loadNextPage(sessionId: String): AppResult<Unit> {
        val cached =
            when (val result = safeStorageCall { local.observe(sessionId).first() }) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            } ?: return AppResult.Success(Unit)
        val cursor = cached.nextCursor ?: return AppResult.Success(Unit)
        val page =
            when (
                val result = safeApiCall {
                    remote
                        .load(sessionId, cursor)
                        .toRecord(Clock.System.now().toEpochMilliseconds())
                        .also {
                            require(it.nextCursor != cursor) { "Pagination cursor did not advance" }
                        }
                }
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return safeStorageCall {
            local.appendPage(sessionId, cached.snapshot, cursor, page.activity, page.nextCursor)
            Unit
        }
    }

    override suspend fun setSaved(
        sessionId: String,
        activityId: String,
        saved: Boolean,
    ): AppResult<Unit> =
        when (
            val stored = safeStorageCall {
                local.setSaved(
                    sessionId,
                    ActivityChangeRecord(Uuid.random().toString(), activityId, saved),
                )
            }
        ) {
            is AppResult.Failed -> stored
            is AppResult.Success ->
                if (stored.value) AppResult.Success(Unit)
                else
                    AppResult.Failed(
                        Failure(FailureKind.Validation, "This activity is no longer available.")
                    )
        }

    override suspend fun pendingChanges(sessionId: String, limit: Int) = safeStorageCall {
        local.pendingChanges(sessionId, limit).map {
            ActivityChange(it.operationId, it.activityId, it.saved)
        }
    }

    override suspend fun push(sessionId: String, change: ActivityChange) = safeApiCall {
        remote.setSaved(
            sessionId,
            change.operationId,
            ActivityPreferenceRequest(change.activityId, change.saved),
        )
    }

    override suspend fun acknowledge(sessionId: String, operationId: String) = safeStorageCall {
        local.acknowledge(sessionId, operationId)
    }
}
