package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.test.*

class CollaborationNotificationFocusTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private val parent =
        CollaborationNativeParent("login", "https://hris.example.test", id(1), id(2))
    private val owner =
        CollaborationOwner(
            parent.sessionId,
            parent.audience,
            parent.accountId,
            id(3),
            parent.nativeSessionId,
        )
    private val candidate =
        CollaborationNotificationCandidate(id(3), id(4), "!room:example.test", "event")

    @Test
    fun onlyExactLocalNativeAccountAudienceCompanyAndConversationCanMatch() {
        val focus = CollaborationNotificationFocus()
        val registration = assertNotNull(focus.register(owner, candidate.conversationId))
        val snapshot = focus.capture()
        assertTrue(snapshot.matches(parent, candidate))
        for (other in
            listOf(
                parent.copy(sessionId = "other"),
                parent.copy(audience = "https://other.example.test"),
                parent.copy(accountId = id(10)),
                parent.copy(nativeSessionId = id(11)),
            )) assertFalse(snapshot.matches(other, candidate))
        assertFalse(snapshot.matches(parent, candidate.copy(companyId = id(12))))
        assertFalse(snapshot.matches(parent, candidate.copy(conversationId = id(13))))
        registration.close()
        assertFalse(focus.current(snapshot))
        assertFalse(focus.capture().matches(parent, candidate))
    }

    @Test
    fun oldRegistrationCannotClearANewerRouteAndRepeatedCloseIsHarmless() {
        val focus = CollaborationNotificationFocus()
        val old = assertNotNull(focus.register(owner, candidate.conversationId))
        val next = assertNotNull(focus.register(owner, candidate.conversationId))
        val snapshot = focus.capture()
        old.close()
        old.close()
        assertTrue(focus.current(snapshot))
        assertTrue(snapshot.matches(parent, candidate))
        next.close()
        assertFalse(focus.current(snapshot))
    }

    @Test
    fun rapidEnterLeaveInvalidatesAnEarlierUnfocusedAttempt() {
        val focus = CollaborationNotificationFocus()
        val before = focus.capture()
        assertNotNull(focus.register(owner, candidate.conversationId)).close()
        assertFalse(focus.current(before))
        assertFalse(focus.capture().matches(parent, candidate))
    }

    @Test
    fun closedOwnerCannotBeReopenedByALateRegistrationOrDisposal() {
        val focus = CollaborationNotificationFocus()
        val registration = assertNotNull(focus.register(owner, candidate.conversationId))
        focus.close()
        registration.close()
        focus.close()
        assertNull(focus.register(owner, candidate.conversationId))
        assertFalse(focus.current(focus.capture()))
    }

    @Test
    fun malformedRouteOwnershipCannotReplaceAnAcceptedRegistration() {
        val focus = CollaborationNotificationFocus()
        assertNotNull(focus.register(owner, candidate.conversationId))
        val before = focus.capture()
        for (invalid in
            listOf(
                owner.copy(sessionId = " "),
                owner.copy(nativeSessionId = "invalid"),
                owner.copy(companyId = "invalid"),
            )) assertNull(focus.register(invalid, candidate.conversationId))
        assertNull(focus.register(owner, "invalid"))
        assertTrue(focus.current(before))
        assertFalse(before.toString().contains(owner.accountId))
    }
}
