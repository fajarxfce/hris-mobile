@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.replica.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.workforce.data.replica.datasources.WorkforceRecordRemoteDataSource
import dev.fajar.starter.workforce.data.replica.mappers.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.replica.repositories.WorkforceRecordRepository
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteWorkforceRecordRepository(private val remote: WorkforceRecordRemoteDataSource) :
    WorkforceRecordRepository {
    override suspend fun attendance(access: WorkforceAccess, id: String) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(id)
        remote.attendance(access.sessionId, access.companyId, id).toAttendanceRecord().also {
            require(it.id == id)
        }
    }

    override suspend fun schedule(access: WorkforceAccess, id: String) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(id)
        remote.schedule(access.sessionId, access.companyId, id).toScheduleRecord().also {
            require(it.id == id)
        }
    }

    override suspend fun roster(access: WorkforceAccess, id: String) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(id)
        remote.roster(access.sessionId, access.companyId, id).toRosterRecord().also {
            require(it.id == id)
        }
    }

    override suspend fun holiday(access: WorkforceAccess, id: String) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(id)
        remote.holiday(access.sessionId, access.companyId, id).toHolidayRecord().also {
            require(it.id == id)
        }
    }
}
