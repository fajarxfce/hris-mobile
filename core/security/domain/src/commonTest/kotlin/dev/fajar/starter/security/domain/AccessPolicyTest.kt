package dev.fajar.starter.security.domain

import dev.fajar.starter.security.domain.access.entities.*
import dev.fajar.starter.security.domain.access.policy.allows
import kotlin.test.*

class AccessPolicyTest {
    private val grant = AccessSnapshot("one", setOf("editor"), setOf(Permission.SaveActivity), 1000)

    @Test
    fun onlyAnExplicitLiveGrantAuthorizesTheAction() {
        assertTrue(allows(grant, Permission.SaveActivity, "one", 999))
        assertFalse(allows(grant, Permission.UploadFile, "one", 999))
        assertFalse(
            allows(grant.copy(permissions = emptySet()), Permission.SaveActivity, "one", 999)
        )
    }

    @Test
    fun expiryAndSessionSwitchDenyPreviouslyAvailableActions() {
        assertFalse(allows(grant, Permission.SaveActivity, "one", 1000))
        assertFalse(allows(grant, Permission.SaveActivity, "two", 999))
        assertFalse(allows(null, Permission.SaveActivity, "one", 999))
    }
}
