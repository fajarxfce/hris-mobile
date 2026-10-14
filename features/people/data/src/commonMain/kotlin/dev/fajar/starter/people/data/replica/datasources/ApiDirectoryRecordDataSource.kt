package dev.fajar.starter.people.data.replica.datasources

import dev.fajar.starter.people.data.api.PeopleApi
import org.koin.core.annotation.Single

@Single
class ApiDirectoryRecordDataSource(private val api: PeopleApi) : DirectoryRecordDataSource {
    override suspend fun record(sessionId: String, companyId: String, id: String) =
        api.directoryRecord(sessionId, companyId, id)
}
