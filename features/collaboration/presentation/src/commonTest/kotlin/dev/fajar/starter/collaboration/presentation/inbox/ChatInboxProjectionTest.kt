package dev.fajar.starter.collaboration.presentation.inbox

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class ChatInboxProjectionTest {
    @Test
    fun pendingPreviewsAreRetainedOnlyForTheSameOwnerAndStillListedConversation() {
        val fixture = ChatInboxFixture()
        val item = fixture.item(1)
        val owner = CollaborationLocalAccess("session", "audience", "account", "company")
        val list = CollaborationLocalList(null, listOf(item), null)
        val preview =
            CollaborationInboxPreview(
                null,
                CollaborationDraft(item.conversation.id, chatId(42), "Saved", fixture.now),
            )
        val state =
            ChatInboxState(
                page = list,
                owner = owner,
                previews = mapOf(item.conversation.id to preview),
            )
        val pending = CollaborationInboxPage(list, owner)
        assertEquals(state.previews, state.withPage(pending).previews)
        for (other in
            listOf(
                owner.copy(sessionId = "new-session"),
                owner.copy(audience = "other-server"),
                owner.copy(accountId = "other-account"),
                owner.copy(companyId = "other-company"),
            )) assertTrue(state.withPage(pending.copy(access = other)).previews.isEmpty())
        assertTrue(
            state.withPage(pending.copy(list = list.copy(items = emptyList()))).previews.isEmpty()
        )
        assertTrue(state.withPage(pending.copy(previewsLoaded = true)).previews.isEmpty())
        assertTrue(
            state
                .withPage(
                    pending.copy(previewFailure = Failure(FailureKind.Storage, "Unavailable"))
                )
                .previews
                .isEmpty()
        )
    }
}
