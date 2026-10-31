package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationConversationValidationTest {
    @Test
    fun rejectsInvalidInputsBeforeTransport() = runTest {
        withConversations(MockEngine { error("Invalid input cannot reach transport") }) { repo ->
            assertIs<AppResult.Failed>(repo.list(setupOwner, "../escape", 30))
            assertIs<AppResult.Failed>(repo.list(setupOwner, null, 51))
            assertIs<AppResult.Failed>(repo.participants(setupOwner, "a".repeat(81), null, 30))
            assertIs<AppResult.Failed>(repo.participants(setupOwner, "a\n", null, 30))
            assertIs<AppResult.Failed>(
                repo.list(setupOwner.copy(audience = "https://other.test/"), null, 30)
            )
            assertIs<AppResult.Failed>(repo.get(setupOwner, "../escape"))
            assertIs<AppResult.Failed>(
                repo.open(
                    setupOwner,
                    CollaborationOpenDirect(SETUP_OPERATION, setupOwner.accountId),
                )
            )
            assertIs<AppResult.Failed>(
                repo.requestRoom(
                    setupOwner,
                    CollaborationRequestDirectRoom(
                        SETUP_OPERATION,
                        CONVERSATION_ID,
                        SETUP_DEVICE,
                        1,
                        999_999,
                    ),
                )
            )
        }
    }

    @Test
    fun rejectsSelfDuplicateBackwardOversizedAndNonProgressingParticipantPages() = runTest {
        for (body in
            listOf(
                """{"items":[${participantJson.replace(PEER_ID, setupOwner.accountId)}],"nextCursor":null}""",
                """{"items":[$participantJson,$participantJson],"nextCursor":null}""",
                """{"items":[$participantJson],"nextCursor":"$CONVERSATION_ID"}""",
                """{"items":[],"nextCursor":"$PEER_ID"}""",
                """{"items":[${participantJson.replace("Fictional coworker", "x".repeat(201))}],"nextCursor":null}""",
            )) withConversations(
            MockEngine {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.participants(setupOwner, "", CONVERSATION_ID, 1))
        }
        withConversations(
            MockEngine {
                respond(
                    """{"items":[$participantJson],"nextCursor":null}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.participants(setupOwner, "", PEER_ID, 30))
        }
    }

    @Test
    fun directResponsesCannotSubstituteAccountCompanyOrRequestedConversation() = runTest {
        for (body in
            listOf(
                conversationJson.replace(setupOwner.companyId, SETUP_DEVICE),
                conversationJson.replace(setupOwner.accountId, SETUP_DEVICE),
                conversationJson.replace(CONVERSATION_ID, SETUP_JOB),
                conversationJson.replace("\"version\":0", "\"version\":1"),
                conversationJson.replace(
                    "\"createdBy\":\"$PEER_ID\"",
                    "\"createdBy\":\"$SETUP_JOB\"",
                ),
            )) withConversations(
            MockEngine {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.get(setupOwner, CONVERSATION_ID))
        }
    }

    @Test
    fun rejectsIncoherentRoomStatesAndForeignRetryAuthority() = runTest {
        for (body in
            listOf(
                roomJson.replace(CONVERSATION_ID, SETUP_JOB),
                roomJson.replace("\"version\":0", "\"version\":null"),
                roomJson.replace("\"state\":\"READY\"", "\"state\":\"QUEUED\""),
                roomJson.replace("!opaque-v12-room", "!bad room"),
                roomJson.replace("\"retryable\":false", "\"retryable\":true"),
                roomJson.replace("\"matrixRoomId\":\"!opaque-v12-room\"", "\"matrixRoomId\":null"),
            )) withConversations(
            MockEngine {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.room(setupOwner, CONVERSATION_ID))
        }
    }

    @Test
    fun unrequestedAndCreatorRetryStatesRemainDistinctIncludingElapsedRetryTime() = runTest {
        val bodies =
            listOf(
                """{"conversationId":"$CONVERSATION_ID","version":null,"state":"NOT_REQUESTED","matrixRoomId":null,"creatorAccountId":null,"retryable":false,"retryAt":null}""",
                """{"conversationId":"$CONVERSATION_ID","version":1,"state":"FAILED","matrixRoomId":null,"creatorAccountId":"${setupOwner.accountId}","retryable":true,"retryAt":"2026-10-01T00:00:00Z"}""",
            )
        for ((index, body) in bodies.withIndex()) withConversations(
            MockEngine {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { repo ->
            val room =
                assertIs<AppResult.Success<CollaborationDirectRoom>>(
                        repo.room(setupOwner, CONVERSATION_ID)
                    )
                    .value
            assertEquals(index == 1, room.retryable)
        }
    }

    @Test
    fun oversizedResponseIsBoundedBeforeDtoAcquisition() = runTest {
        withConversations(
            MockEngine {
                respond(
                    " ".repeat(131073),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.participants(setupOwner, "", null, 30))
        }
    }

    @Test
    fun mutationReceiptCannotChangeRoomIdentityOrInventAnUpdatedDirectIdentity() = runTest {
        withConversations(
            MockEngine {
                respond(
                    """{"id":"$SETUP_JOB","version":1}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) { repo ->
            assertIs<AppResult.Failed>(
                repo.open(setupOwner, CollaborationOpenDirect(SETUP_OPERATION, PEER_ID))
            )
            assertIs<AppResult.Failed>(
                repo.requestRoom(
                    setupOwner,
                    CollaborationRequestDirectRoom(
                        SETUP_OPERATION,
                        CONVERSATION_ID,
                        SETUP_DEVICE,
                        1,
                        null,
                    ),
                )
            )
        }
    }
}
