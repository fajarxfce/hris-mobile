package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

private fun summaryJson(conversation: String = conversationJson, peer: String = participantJson) =
    """{"conversation":$conversation,"peer":$peer}"""

private fun summaryPage(items: String, next: String? = null) =
    """{"items":[$items],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}"""

class CollaborationSummaryTransportTest {
    @Test
    fun summaryPageAndDetailBindTheNativeOwnerWithoutPerRowRequests() = runTest {
        var calls = 0
        val after = "77777777-7777-4777-8777-777777777777"
        withConversations(
            MockEngine { request ->
                val root =
                    "/api/v1/companies/${setupOwner.companyId}/collaboration/direct-conversations"
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(
                    "Bearer fictional-old-native",
                    request.headers[HttpHeaders.Authorization],
                )
                val body =
                    if (calls++ == 0) {
                        assertEquals("$root/summaries", request.url.encodedPath)
                        assertEquals(after, request.url.parameters["after"])
                        assertEquals("1", request.url.parameters["limit"])
                        summaryPage(summaryJson(), CONVERSATION_ID)
                    } else {
                        assertEquals("$root/$CONVERSATION_ID/summary", request.url.encodedPath)
                        assertTrue(request.url.parameters.isEmpty())
                        summaryJson()
                    }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { repository ->
            val page =
                assertIs<AppResult.Success<CollaborationPage<CollaborationDirectSummary>>>(
                        repository.summaries(setupOwner, after, 1)
                    )
                    .value
            val single =
                assertIs<AppResult.Success<CollaborationDirectSummary>>(
                        repository.summary(setupOwner, CONVERSATION_ID)
                    )
                    .value
            assertEquals(page.items.single(), single)
            assertEquals(CONVERSATION_ID, single.conversation.id)
            assertEquals(CollaborationParticipant(PEER_ID, "Fictional coworker"), single.peer)
            assertEquals(CONVERSATION_ID, page.nextCursor)
            assertEquals(2, calls)
        }
    }

    @Test
    fun summaryMappingRejectsForeignCounterpartsAndUnusableLabels() = runTest {
        val peer = Json.parseToJsonElement(participantJson).jsonObject
        fun label(value: String) =
            JsonObject(peer + ("displayName" to JsonPrimitive(value))).toString()
        val invalid =
            listOf(
                summaryJson(
                    peer = participantJson.replace(PEER_ID, "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
                ),
                summaryJson(peer = participantJson.replace(PEER_ID, setupOwner.accountId)),
                summaryJson(conversation = conversationJson.replace(setupOwner.companyId, PEER_ID)),
                summaryJson(conversation = conversationJson.replace(CONVERSATION_ID, PEER_ID)),
                summaryJson(peer = label("")),
                summaryJson(peer = label("Invalid\nlabel")),
                summaryJson(peer = label("n".repeat(201))),
            )
        for (body in invalid) {
            var calls = 0
            withConversations(
                MockEngine {
                    calls++
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) { repository ->
                assertIs<AppResult.Failed>(repository.summary(setupOwner, CONVERSATION_ID))
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun summaryPagesCannotPublishDuplicatesExcessRowsOrNonAdvancingCursors() = runTest {
        val item = summaryJson()
        val before = "77777777-7777-4777-8777-777777777777"
        val cases =
            listOf(
                Triple(summaryPage("$item,$item"), null, 2),
                Triple(summaryPage("$item,$item"), null, 1),
                Triple(summaryPage(item, PEER_ID), null, 1),
                Triple(summaryPage(item), CONVERSATION_ID, 1),
                Triple(
                    summaryPage(
                        "$item,${summaryJson(conversation = conversationJson.replace(CONVERSATION_ID, before))}"
                    ),
                    null,
                    2,
                ),
            )
        for ((body, after, limit) in cases) {
            withConversations(
                MockEngine {
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) {
                assertIs<AppResult.Failed>(it.summaries(setupOwner, after, limit))
            }
        }
    }

    @Test
    fun summaryReadsBoundInputsAndResponseBodies() = runTest {
        withConversations(MockEngine { error("Invalid input must not reach HTTP") }) {
            assertIs<AppResult.Failed>(it.summary(setupOwner, "../../foreign"))
            assertIs<AppResult.Failed>(it.summaries(setupOwner, "invalid", 30))
            assertIs<AppResult.Failed>(it.summaries(setupOwner, null, 51))
        }
        for (single in listOf(false, true)) {
            val body =
                (if (single) summaryJson() else summaryPage(summaryJson())) +
                    " ".repeat(if (single) 4096 else 131072)
            var calls = 0
            withConversations(
                MockEngine {
                    calls++
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) {
                assertIs<AppResult.Failed>(
                    if (single) it.summary(setupOwner, CONVERSATION_ID)
                    else it.summaries(setupOwner, null, 1)
                )
                assertEquals(1, calls)
            }
        }
    }
}
