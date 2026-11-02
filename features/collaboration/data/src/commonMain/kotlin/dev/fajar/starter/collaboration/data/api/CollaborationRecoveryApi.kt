@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class CollaborationRecoveryApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun devices(
        sessionId: String,
        afterCompanyId: String?,
        limit: Int,
    ): CollaborationDevicePageDto =
        client.requestJson("collaboration/native-devices", maximumBytes = 16_384) {
            forSession(sessionId)
            parameter("limit", limit)
            afterCompanyId?.let { parameter("afterCompanyId", Uuid.parse(it).toString()) }
        }

    suspend fun admission(sessionId: String, companyId: String): CollaborationAdmissionDto =
        client.requestJson(
            "companies/${Uuid.parse(companyId)}/collaboration/native-device/admission",
            maximumBytes = 2048,
        ) {
            forSession(sessionId)
        }
}
