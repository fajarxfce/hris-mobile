package dev.fajar.starter.people.data.profile.datasources

import dev.fajar.starter.people.data.api.PeopleApi
import org.koin.core.annotation.Single

@Single
class ApiSelfProfileRecordDataSource(private val api: PeopleApi) : SelfProfileRecordDataSource {
    override suspend fun record(sessionId: String, companyId: String, id: String) =
        api.selfProfileRecord(sessionId, companyId, id)
}
