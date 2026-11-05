@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class CollaborationSummarySyncApi(
    @Named(HttpClients.Authenticated) private val client: HttpClient
) {
    suspend fun bootstrap(
        session: String,
        company: String,
        cursor: String?,
        limit: Int,
    ): CollaborationSummaryBootstrapDto =
        client.requestJson(path(company) + "/bootstrap", maximumBytes = 131072) {
            page(session, cursor, limit)
        }

    suspend fun changes(
        session: String,
        company: String,
        cursor: String,
        limit: Int,
    ): CollaborationSummaryChangesDto =
        client.requestJson(path(company) + "/changes", maximumBytes = 131072) {
            page(session, cursor, limit)
        }

    private fun path(company: String) =
        "companies/${Uuid.parse(company)}/collaboration/direct-conversations/summary-sync"

    private fun HttpRequestBuilder.page(session: String, cursor: String?, limit: Int) {
        forSession(session)
        parameter("limit", limit)
        cursor?.let { parameter("cursor", it) }
    }
}
