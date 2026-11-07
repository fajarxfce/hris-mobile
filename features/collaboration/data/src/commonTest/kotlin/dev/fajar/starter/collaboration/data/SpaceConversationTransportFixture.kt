package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.*
import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.toSpaceConversation
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.*
import kotlin.test.assertEquals

internal class SpaceConversationTransportFixture {
    val owner =
        CollaborationSpaceOwner(
            CollaborationNativeParent(
                setupOwner.sessionId,
                setupOwner.audience,
                setupOwner.accountId,
                setupOwner.nativeSessionId,
            ),
            "70000000-0000-4000-8000-000000000001",
        )
    val peer = "80000000-0000-4000-8000-000000000001"
    val id = "a0000000-0000-4000-8000-000000000001"
    val other = "b0000000-0000-4000-8000-000000000001"
    val operation = "c0000000-0000-4000-8000-000000000001"
    val device = "d0000000-0000-4000-8000-000000000001"
    private val pair = listOf(owner.parent.accountId, peer).sorted()
    val created = "2026-11-07T00:00:00.123456Z"
    val requested = "2026-11-07T00:00:01.123456Z"
    val completed = "2026-11-07T00:00:02.123456Z"
    val dto =
        CollaborationSpaceConversationDto(id, owner.spaceId, pair[0], pair[1], peer, created, 0)
    val conversation = dto.toSpaceConversation(owner)
    val queued =
        CollaborationSpaceRoomStatusDto(
            owner.spaceId,
            id,
            0,
            "QUEUED",
            0,
            requested,
            requested,
            "2026-11-07T00:05:01.123456Z",
            null,
            null,
            null,
            peer,
        )
    val success =
        queued.copy(
            state = "SUCCEEDED",
            attempts = 1,
            completedAt = completed,
            lastOutcome = CollaborationSpaceRoomOutcomeDto(0, "SUCCEEDED", completed, null),
        )
    val published =
        CollaborationSpacePublishedRoomDto(
            "!original:other.test",
            "matrix.example.test:8448",
            "LEGACY",
            completed,
        )
    val view = CollaborationSpaceRoomViewDto(owner.spaceId, id, success, published)
    val headers = headersOf(HttpHeaders.ContentType, "application/json")
    val root = "/api/v1/collaboration/spaces/${owner.spaceId}/direct-conversations"

    suspend fun withRepositories(
        engine: MockEngine,
        action:
            suspend (
                RemoteCollaborationSpaceConversationRepository,
                RemoteCollaborationSpaceRoomRepository,
            ) -> Unit,
    ) {
        val client =
            createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
                install(SessionAuthentication) {
                    origin = Url(SETUP_BASE)
                    acquire = { session, rejected ->
                        assertEquals(owner.parent.sessionId, session)
                        AppResult.Success(
                            if (rejected == null) "fictional-native" else "rotated-native"
                        )
                    }
                }
            }
        try {
            val server = IdentityServer(SETUP_BASE, "Fictional native")
            action(
                RemoteCollaborationSpaceConversationRepository(
                    ApiCollaborationSpaceConversationRemoteSource(
                        CollaborationSpaceConversationApi(client)
                    ),
                    server,
                ),
                RemoteCollaborationSpaceRoomRepository(
                    ApiCollaborationSpaceRoomRemoteSource(CollaborationSpaceRoomApi(client)),
                    server,
                ),
            )
        } finally {
            client.cancelAndClose()
        }
    }
}
