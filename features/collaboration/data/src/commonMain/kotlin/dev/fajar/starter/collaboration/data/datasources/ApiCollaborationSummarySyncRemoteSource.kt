package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSummarySyncApi
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSummarySyncRemoteSource(private val api: CollaborationSummarySyncApi) :
    CollaborationSummarySyncRemoteSource {
    override suspend fun bootstrap(session: String, company: String, cursor: String?, limit: Int) =
        api.bootstrap(session, company, cursor, limit)

    override suspend fun changes(session: String, company: String, cursor: String, limit: Int) =
        api.changes(session, company, cursor, limit)
}
