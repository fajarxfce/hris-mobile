package dev.fajar.starter.workforce.data.replica.datasources

import dev.fajar.starter.workforce.data.replica.api.WorkforceRecordApi
import org.koin.core.annotation.Single

@Single
class ApiWorkforceRecordDataSource(private val api: WorkforceRecordApi) :
    WorkforceRecordRemoteDataSource {
    override suspend fun attendance(sessionId: String, companyId: String, id: String) =
        api.attendance(sessionId, companyId, id)

    override suspend fun schedule(sessionId: String, companyId: String, id: String) =
        api.schedule(sessionId, companyId, id)

    override suspend fun roster(sessionId: String, companyId: String, id: String) =
        api.roster(sessionId, companyId, id)

    override suspend fun holiday(sessionId: String, companyId: String, id: String) =
        api.holiday(sessionId, companyId, id)
}
