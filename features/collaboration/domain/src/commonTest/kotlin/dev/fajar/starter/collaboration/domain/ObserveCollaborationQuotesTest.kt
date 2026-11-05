package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationQuotes
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class ObserveCollaborationQuotesTest {
    @Test
    fun oldOwnedSessionReadsQuotesLocallyButALateDenialWinsOverFailedSessionRecovery() = runTest {
        val f = QuoteFixture()
        f.owner.setup.now += 365.days
        assertEquals(AppResult.Success(listOf(f.quote)), f.observe().first())
        assertEquals(0, f.owner.setup.calls)
        assertEquals(0, f.active)
        f.before = {
            f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
            f.owner.setup.sessionFailure = f.owner.storage
        }
        assertEquals(AppResult.Failed(f.owner.denied), f.observe().first())
        assertEquals(0, f.active)
    }

    @Test
    fun foreignScopeDuplicateQueriesAndMalformedRepositoryQuotesNeverReachTheView() = runTest {
        val f = QuoteFixture()
        assertIs<AppResult.Failed>(f.observe(listOf(f.quote.eventId, f.quote.eventId)).first())
        assertIs<AppResult.Failed>(f.observe(listOf("invalid")).first())
        assertIs<AppResult.Failed>(f.observe(List(129) { "\$event-$it" }).first())
        assertEquals(0, f.reads)
        for (bad in
            listOf(
                listOf(f.quote, f.quote),
                listOf(f.quote.copy(eventId = "\$foreign")),
                listOf(f.quote.copy(text = "")),
                listOf(f.quote.copy(sender = "invalid")),
            )) {
            f.result.value = AppResult.Success(bad)
            assertIs<AppResult.Failed>(f.observe().first())
            assertEquals(0, f.active)
        }
        val before = f.reads
        assertIs<AppResult.Failed>(
            f.observe(expected = f.access.copy(accountId = "another-account")).first()
        )
        assertEquals(before, f.reads)
    }
}

private class QuoteFixture {
    val owner = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            owner.setup.session!!.id,
            owner.server.audience,
            owner.setup.session!!.user.id,
            owner.setup.workspace!!.company.id,
        )
    val conversation = owner.summary!!.conversation.id
    val room = "!local:example.test"
    val quote = CollaborationQuotedMessage("\$original", "@peer:example.test", "Local original")
    val result =
        MutableStateFlow<AppResult<List<CollaborationQuotedMessage>>>(
            AppResult.Success(listOf(quote))
        )
    var reads = 0
    var active = 0
    var before: suspend () -> Unit = {}
    val repository =
        object : CollaborationQuoteRepository {
            override fun observeQuotes(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventIds: List<String>,
            ) = flow {
                assertEquals(this@QuoteFixture.access, access)
                assertEquals(conversation, conversationId)
                assertEquals(room, roomId)
                reads++
                active++
                try {
                    result.collect {
                        before()
                        emit(it)
                    }
                } finally {
                    active--
                }
            }
        }
    val reader =
        ObserveCollaborationQuotes(
            repository,
            owner.lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
        )

    fun observe(
        ids: List<String> = listOf(quote.eventId),
        expected: CollaborationLocalAccess = access,
    ) = reader("selection", expected, conversation, room, ids)
}
