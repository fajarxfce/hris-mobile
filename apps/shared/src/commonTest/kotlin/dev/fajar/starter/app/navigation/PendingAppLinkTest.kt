package dev.fajar.starter.app.navigation

import dev.fajar.starter.identity.domain.entities.CompanyMembership
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.entities.WorkspaceAdmission
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class PendingAppLinkTest {
    private val now = Instant.parse("2026-10-28T00:00:00Z")
    private val workspace =
        Workspace(
            "workspace",
            "session",
            "https://hris.example.test/",
            CompanyMembership("company", "Example", "EX", "Asia/Jakarta"),
            emptySet(),
            WorkspaceAdmission("account", "family", now - 3.days, now - 2.days),
        )

    @Test
    fun anExternalIntentCanWaitForItsFirstLoginAndCompanyWithoutAnAgeLimit() {
        val original = PendingAppLink(AppLink.InboxItem("account", "company", "item"))
        assertEquals(original, original.inContext(null, null))
        val signedIn = requireNotNull(original.inContext("session", null))
        assertEquals("session", signedIn.sessionId)
        assertNull(signedIn.workspaceId)
        val selected = requireNotNull(signedIn.inContext("session", workspace))
        assertEquals("workspace", selected.workspaceId)
        assertEquals(
            selected,
            selected.inContext(
                "session",
                workspace.copy(admission = workspace.admission.copy(expiresAt = now)),
            ),
        )
    }

    @Test
    fun aBoundIntentCannotMoveAcrossLogoutSessionReplacementOrCompanySelection() {
        val pending = requireNotNull(PendingAppLink(AppLink.Inbox).inContext("session", workspace))
        assertNull(pending.inContext(null, null))
        assertNull(pending.inContext("replacement", workspace.copy(sessionId = "replacement")))
        assertNull(pending.inContext("session", null))
        assertNull(pending.inContext("session", workspace.copy(id = "new-selection")))
        assertNull(PendingAppLink(AppLink.Inbox).inContext("replacement", workspace))
    }

    @Test
    fun aResourceLinkCannotSelectAResourceInAnotherAccountOrCompany() {
        for (target in
            listOf(
                AppLink.InboxItem("other", "company", "item"),
                AppLink.InboxItem("account", "other", "item"),
            )) {
            assertNull(PendingAppLink(target).inContext("session", workspace))
            val preselection = requireNotNull(PendingAppLink(target).inContext("session", null))
            assertNull(preselection.inContext("session", workspace))
        }
    }
}
