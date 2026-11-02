package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.collaboration.domain.usecases.RefreshCollaborationList
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.SignIn
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class CollaborationRoomLookupIntegrationTest {
    @Test
    fun actualContainerResolvesRoomOutsideTheListedPageAndRetainsItWithoutSelectionOrSdk() =
        runBlocking {
            val f = CollaborationWorkerFixture()
            try {
                assertIs<AppResult.Success<*>>(
                    f.koin.get<SignIn>()(
                        "alex@example.com",
                        "Fictional-password-123!",
                        UUID.randomUUID().toString(),
                    )
                )
                f.session =
                    assertNotNull(
                        (f.koin.get<SessionRepository>().current() as AppResult.Success).value
                    )
                f.withoutIncomingSelection()
                f.server.collaboration.listedIds = emptyList()
                f.server.collaboration.reads.clear()
                val grant = f.incomingGrant()
                assertNull(
                    (f.koin
                            .get<CollaborationListRepository>()
                            .get(grant.owner.localListAccess(), f.first) as AppResult.Success)
                        .value
                )
                val room = f.server.collaboration.roomId(f.first)
                val result =
                    assertIs<AppResult.Success<CollaborationRead<CollaborationDirectSummary>>>(
                            f.koin
                                .get<RefreshCollaborationList>()
                                .byRoom(CollaborationOperationScope.Background(grant), room)
                        )
                        .value
                assertEquals(f.first, result.value.conversation.id)
                assertTrue(f.server.collaboration.reads.single().endsWith("/summary-by-room"))
                assertEquals(0, f.sdk.restores)
                assertEquals(0, f.sdk.historyReads)
                assertTrue(f.server.collaboration.roomRequests.isEmpty())
                f.reopen()
                assertEquals(
                    f.session.id,
                    (f.koin.get<SessionRepository>().current() as AppResult.Success).value?.id,
                )
                val saved =
                    f.koin
                        .get<CollaborationListRepository>()
                        .get(grant.owner.localListAccess(), f.first)
                assertEquals(AppResult.Success(result.value), saved)
                assertTrue(f.server.unexpectedPaths.isEmpty())
            } finally {
                f.close()
            }
        }
}
