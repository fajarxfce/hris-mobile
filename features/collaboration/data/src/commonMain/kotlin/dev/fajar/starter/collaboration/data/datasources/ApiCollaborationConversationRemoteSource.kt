package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationConversationApi
import dev.fajar.starter.collaboration.data.dto.*
import org.koin.core.annotation.Single

@Single
class ApiCollaborationConversationRemoteSource(private val api: CollaborationConversationApi) :
    CollaborationConversationRemoteSource {
    override suspend fun participants(
        session: String,
        company: String,
        query: String,
        after: String?,
        limit: Int,
    ) = api.participants(session, company, query, after, limit)

    override suspend fun list(session: String, company: String, after: String?, limit: Int) =
        api.list(session, company, after, limit)

    override suspend fun get(session: String, company: String, id: String) =
        api.get(session, company, id)

    override suspend fun room(session: String, company: String, id: String) =
        api.room(session, company, id)

    override suspend fun open(
        session: String,
        company: String,
        operation: String,
        input: OpenCollaborationDirectRequest,
    ) = api.open(session, company, operation, input)

    override suspend fun requestRoom(
        session: String,
        company: String,
        id: String,
        operation: String,
        input: RequestCollaborationDirectRoomRequest,
    ) = api.requestRoom(session, company, id, operation, input)
}
