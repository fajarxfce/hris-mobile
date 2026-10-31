package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationSetupApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationSetupRemoteSource
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationSetupRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.Url
import kotlin.test.assertEquals

internal const val SETUP_BASE = "https://hris.example.test/api/v1/"
internal const val SETUP_DEVICE = "44444444-4444-4444-8444-444444444444"
internal const val SETUP_OPERATION = "55555555-5555-4555-8555-555555555555"
internal const val SETUP_GATEWAY = "66666666-6666-4666-8666-666666666666"
internal const val SETUP_JOB = "77777777-7777-4777-8777-777777777777"
internal val setupOwner =
    CollaborationOwner(
        "local-session",
        SETUP_BASE,
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333",
    )
internal val setupBootstrap = CollaborationBootstrap(SETUP_OPERATION, SETUP_DEVICE, 1, null)
internal val setupUser =
    "@hris_${setupOwner.companyId.replace("-", "")}_${setupOwner.accountId.replace("-", "")}:matrix.example.test"
internal val setupAccess = "hris_mx_a_" + "a".repeat(43)
internal val setupRefresh = "hris_mx_r_" + "b".repeat(43)

internal val setupGatewayJson =
    """{"sessionId":"$SETUP_GATEWAY","version":0,"matrixUserId":"$setupUser","matrixDeviceId":"HRIS_${SETUP_DEVICE.replace("-", "")}","accessToken":"$setupAccess","refreshToken":"$setupRefresh","accessExpiresAt":"2026-10-31T00:05:00Z","expiresAt":"2026-11-30T00:00:00Z"}"""
internal val setupDeviceJson =
    """{"device":{"id":"$SETUP_DEVICE","version":1,"state":"ACTIVE","platform":"ANDROID","provisioning":{"version":0,"jobId":"$SETUP_JOB","status":"SUCCEEDED","attempts":1,"failureCode":null,"retryNotBefore":null},"gateway":{"id":"$SETUP_GATEWAY","version":0,"clientBuild":5,"accessExpiresAt":"2026-10-01T00:05:00Z","expiresAt":"2026-10-31T00:00:00Z","revokedAt":null}}}"""

internal suspend fun withSetupRepository(
    engine: MockEngine,
    action: suspend (RemoteCollaborationSetupRepository) -> Unit,
) {
    val client =
        createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
            install(SessionAuthentication) {
                origin = Url(SETUP_BASE)
                acquire = { session, rejected ->
                    assertEquals(setupOwner.sessionId, session)
                    AppResult.Success(
                        if (rejected == null) "fictional-old-native" else "fictional-renewed-native"
                    )
                }
            }
        }
    try {
        action(
            RemoteCollaborationSetupRepository(
                ApiCollaborationSetupRemoteSource(CollaborationSetupApi(client)),
                IdentityServer(SETUP_BASE, "Fictional Android"),
            )
        )
    } finally {
        client.close()
    }
}
