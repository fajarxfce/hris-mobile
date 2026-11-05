package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationSummarySyncApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationSummarySyncRemoteSource
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationSummarySyncRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

private fun fact(
    revision: Long = 0,
    summary: String? = """{"conversation":$conversationJson,"peer":$participantJson}""",
) = """{"conversationId":"$CONVERSATION_ID","revision":$revision,"summary":${summary?:"null"}}"""

private fun initial(
    items: String = fact(),
    next: String? = null,
    changes: String? = "opaque-changes",
    pending: Boolean = false,
) =
    """{"items":[$items],"nextPage":${next?.let { JsonPrimitive(it) }?:JsonNull},"changesCursor":${changes?.let { JsonPrimitive(it) }?:JsonNull},"pending":$pending,"serverTime":"2026-11-05T00:00:00Z"}"""

private fun delta(items: String = "", more: Boolean = false, pending: Boolean = false) =
    """{"items":[$items],"nextCursor":"opaque-next","hasMore":$more,"pending":$pending,"serverTime":"2026-11-05T00:00:00Z"}"""

private fun change(
    position: Long,
    revision: Long = 0,
    summary: String? = """{"conversation":$conversationJson,"peer":$participantJson}""",
) = """{"position":$position,"fact":${fact(revision,summary)}}"""

private suspend fun withSummarySync(
    engine: MockEngine,
    action: suspend (RemoteCollaborationSummarySyncRepository) -> Unit,
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
            RemoteCollaborationSummarySyncRepository(
                ApiCollaborationSummarySyncRemoteSource(CollaborationSummarySyncApi(client)),
                IdentityServer(SETUP_BASE, "Fictional Android"),
            )
        )
    } finally {
        client.close()
    }
}

class CollaborationSummarySyncTransportTest {
    @Test
    fun bootstrapAndDeltasUseNativeScopeAndPreserveTombstonesWithoutPerRowReads() = runTest {
        var calls = 0
        val opaque = "opaque:+/=&%?"
        withSummarySync(
            MockEngine { request ->
                val root =
                    "/api/v1/companies/${setupOwner.companyId}/collaboration/direct-conversations/summary-sync"
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(
                    "Bearer fictional-old-native",
                    request.headers[HttpHeaders.Authorization],
                )
                assertEquals("2", request.url.parameters["limit"])
                val body =
                    when (calls++) {
                        0 -> {
                            assertEquals("$root/bootstrap", request.url.encodedPath)
                            assertNull(request.url.parameters["cursor"])
                            initial(pending = true)
                        }
                        else -> {
                            assertEquals("$root/changes", request.url.encodedPath)
                            assertEquals(opaque, request.url.parameters["cursor"])
                            delta(change(2, 1, null) + "," + change(3, 2), pending = true)
                        }
                    }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { repository ->
            val first =
                assertIs<AppResult.Success<CollaborationSummaryBootstrap>>(
                        repository.bootstrap(setupOwner, null, 2)
                    )
                    .value
            assertEquals(0L, first.items.single().revision)
            assertTrue(first.pending)
            val next =
                assertIs<AppResult.Success<CollaborationSummaryChanges>>(
                        repository.changes(setupOwner, opaque, 2)
                    )
                    .value
            assertEquals(listOf(2L, 3L), next.items.map { it.position })
            assertNull(next.items.first().fact.summary)
            assertEquals(2L, next.items.last().fact.revision)
            assertEquals(0L, next.items.last().fact.summary?.conversation?.version)
            assertEquals(2, calls)
        }
    }

    @Test
    fun emptyChangesAndCompletedEmptyBootstrapAreValidButIncompleteEmptyPagesAreNot() = runTest {
        for ((body, bootstrap, valid) in
            listOf(
                Triple(initial(items = ""), true, true),
                Triple(delta(), false, true),
                Triple(initial(items = "", next = "more", changes = null), true, false),
                Triple(delta(more = true), false, false),
                Triple(initial(next = "more"), true, false),
                Triple(initial(changes = null), true, false),
            )) {
            withSummarySync(
                MockEngine {
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) { repository ->
                val result =
                    if (bootstrap) repository.bootstrap(setupOwner, null, 2)
                    else repository.changes(setupOwner, "opaque", 2)
                if (valid) assertIs<AppResult.Success<*>>(result)
                else assertIs<AppResult.Failed>(result)
            }
        }
    }

    @Test
    fun malformedOrderVersionsCounterpartsAndCrossCompanyFactsCannotBePublished() = runTest {
        val wrongCompany = fact().replace(setupOwner.companyId, PEER_ID)
        val wrongTarget =
            fact()
                .replace(
                    "\"conversationId\":\"$CONVERSATION_ID\"",
                    "\"conversationId\":\"$PEER_ID\"",
                )
        val malformed =
            listOf(
                initial(fact() + "," + fact()),
                initial(fact(-1)),
                initial(wrongCompany),
                initial(wrongTarget),
                initial(fact().replace("Fictional coworker", "")),
                initial(next = "bad\n", changes = null),
            )
        for (body in malformed) withSummarySync(
            MockEngine {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) {
            assertIs<AppResult.Failed>(it.bootstrap(setupOwner, null, 2))
        }
        for (body in
            listOf(
                delta(change(0)),
                delta(change(2) + "," + change(2)),
                delta(change(3) + "," + change(2)),
                delta(change(1, -1, null)),
                delta(change(1)).replace("opaque-next", ""),
                delta(change(1)).replace("2026-11-05T00:00:00Z", "invalid"),
            )) {
            withSummarySync(
                MockEngine {
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) {
                assertIs<AppResult.Failed>(it.changes(setupOwner, "opaque", 2))
            }
        }
    }

    @Test
    fun nativeRefreshKeepsTheExactOpaqueCursorAndMakesNoBootstrapRequest() = runTest {
        var calls = 0
        withSummarySync(
            MockEngine { request ->
                assertTrue(request.url.encodedPath.endsWith("/changes"))
                assertEquals("original:+/cursor", request.url.parameters["cursor"])
                if (calls++ == 0)
                    respond(
                        """{"code":"authentication_required"}""",
                        HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                else {
                    assertEquals(
                        "Bearer fictional-renewed-native",
                        request.headers[HttpHeaders.Authorization],
                    )
                    respond(
                        delta(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        ) {
            assertIs<AppResult.Success<CollaborationSummaryChanges>>(
                it.changes(setupOwner, "original:+/cursor", 2)
            )
            assertEquals(2, calls)
        }
    }

    @Test
    fun denialResetAndCancellationRemainClassifiedWithoutAdapterRetries() = runTest {
        for ((status, code, kind) in
            listOf(
                Triple(HttpStatusCode.Forbidden, "access_denied", FailureKind.AccessDenied),
                Triple(
                    HttpStatusCode.Conflict,
                    "collaboration_summary_cursor_expired",
                    FailureKind.Conflict,
                ),
            )) {
            var calls = 0
            withSummarySync(
                MockEngine {
                    calls++
                    respond(
                        """{"code":"$code","detail":"private fixture"}""",
                        status,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            ) {
                val failed = assertIs<AppResult.Failed>(it.changes(setupOwner, "opaque", 2)).failure
                assertEquals(kind, failed.kind)
                assertEquals(code, failed.code)
                assertFalse(failed.message.contains("private fixture"))
                assertEquals(1, calls)
            }
        }
        withSummarySync(MockEngine { throw CancellationException("Owned request stopped") }) {
            assertFailsWith<CancellationException> { it.changes(setupOwner, "opaque", 2) }
        }
    }

    @Test
    fun requestAndResponseSizesAreBoundedBeforeAnyLocalPublication() = runTest {
        withSummarySync(MockEngine { error("Invalid input reached HTTP") }) { repository ->
            for (cursor in listOf("", "x".repeat(2049), "a\nb")) assertIs<AppResult.Failed>(
                repository.changes(setupOwner, cursor, 2)
            )
            for (limit in listOf(0, 51)) assertIs<AppResult.Failed>(
                repository.bootstrap(setupOwner, null, limit)
            )
            assertIs<AppResult.Failed>(
                repository.bootstrap(setupOwner.copy(audience = "https://foreign.invalid"), null, 2)
            )
            assertIs<AppResult.Failed>(
                repository.bootstrap(setupOwner.copy(companyId = "../foreign"), null, 2)
            )
        }
        for (bootstrap in listOf(true, false)) withSummarySync(
            MockEngine {
                respond(
                    (if (bootstrap) initial() else delta()) + " ".repeat(131072),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) {
            assertIs<AppResult.Failed>(
                if (bootstrap) it.bootstrap(setupOwner, null, 2)
                else it.changes(setupOwner, "opaque", 2)
            )
        }
    }
}
