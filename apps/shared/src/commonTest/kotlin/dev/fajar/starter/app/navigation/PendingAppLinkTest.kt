package dev.fajar.starter.app.navigation

import dev.fajar.starter.identity.domain.entities.CompanyMembership
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.entities.WorkspaceAdmission
import dev.fajar.starter.workspace.domain.entities.WorkspaceSelectionOwner
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

    @Test
    fun aNativeChatLinkNeedsTheActualFourPartOwnerAndExactCompanySelection() {
        val owner = WorkspaceSelectionOwner("session", workspace.audience, "account", "family")
        val link =
            PendingAppLink(
                AppLink.ChatThread(owner, "company", "conversation"),
                "session",
                "workspace",
            )
        assertEquals(link, link.inContext("session", workspace, owner))
        assertNull(link.inContext("session", workspace))
        for (wrong in
            listOf(
                owner.copy(sessionId = "replacement"),
                owner.copy(audience = "https://other.invalid/"),
                owner.copy(accountId = "other"),
                owner.copy(familyId = "other"),
            )) assertNull(link.inContext("session", workspace, wrong))
        assertNull(
            link.inContext(
                "session",
                workspace.copy(company = workspace.company.copy(id = "other")),
                owner,
            )
        )
        assertNull(link.inContext("session", workspace.copy(id = "replacement"), owner))
        assertNull(
            link.inContext(
                "session",
                workspace.copy(admission = workspace.admission.copy(familyId = "other")),
                owner,
            )
        )
        assertNull(link.inContext(null, null, owner))
    }
}
