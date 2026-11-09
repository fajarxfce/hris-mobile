package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceDirectTransportApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationSpaceDirectTransportRemoteSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationSpaceDirectTransportRepository
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.Url
import kotlin.test.assertEquals

internal class SpaceDirectTransportFixture {
    val pair = SpaceConversationTransportFixture()
    val observed = "2026-11-07T00:01:00Z"
    val originalCompany = requireNotNull(pair.published.original).companyId.replace("-", "")
    val account = pair.owner.parent.accountId.replace("-", "")
    val user = "@hris_${originalCompany}_$account:${pair.published.serverName}"
    val body =
        CollaborationSpaceDirectTransportDto(
            pair.owner.spaceId,
            pair.owner.parent.accountId,
            pair.owner.parent.nativeSessionId,
            7,
            pair.dto,
            CollaborationSpaceDeviceDto(
                pair.device,
                2,
                "ACTIVE",
                "ANDROID",
                "LEGACY",
                "Fixture phone",
                CollaborationSpaceProvisioningDto(
                    0,
                    "SUCCEEDED",
                    1,
                    "2026-11-07T00:05:00Z",
                    CollaborationSpaceProvisioningOutcomeDto(0, "SUCCEEDED", pair.completed, null),
                    null,
                ),
                observed,
                CollaborationGatewayStatusDto(
                    SETUP_GATEWAY,
                    3,
                    29,
                    "2026-11-07T00:00:30Z",
                    "2026-11-07T01:00:00Z",
                    null,
                ),
            ),
            user,
            "HRIS_${SETUP_DEVICE.replace("-", "")}",
            pair.published,
            false,
        )

    suspend fun withRepository(
        engine: MockEngine,
        action: suspend (RemoteCollaborationSpaceDirectTransportRepository) -> Unit,
    ) {
        val client =
            createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
                defaultRequest {
                    header("X-HRIS-Client-Platform", "ANDROID")
                    header("X-HRIS-Client-Build", 29)
                }
                install(SessionAuthentication) {
                    origin = Url(SETUP_BASE)
                    acquire = { session, rejected ->
                        assertEquals(pair.owner.parent.sessionId, session)
                        AppResult.Success(
                            if (rejected == null) "fictional-native" else "renewed-native"
                        )
                    }
                }
            }
        try {
            action(
                RemoteCollaborationSpaceDirectTransportRepository(
                    ApiCollaborationSpaceDirectTransportRemoteSource(
                        CollaborationSpaceDirectTransportApi(client)
                    ),
                    IdentityServer(SETUP_BASE, "Fixture"),
                )
            )
        } finally {
            client.cancelAndClose()
        }
    }
}
