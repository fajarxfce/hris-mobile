package dev.fajar.starter.identity.domain

import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import kotlin.test.*

class AccountStorageKeyTest {
    private val session =
        Session(
            "session-a",
            User("account-a", "Display name", "private@example.invalid"),
            SessionTokens("access", "refresh", 1000),
            SessionBinding("server", "https://api.example.invalid/v1/", 1000),
        )

    @Test
    fun ownershipRemainsStableAcrossSessionsAndCredentialRotation() {
        val later =
            session.copy(
                id = "session-b",
                user = session.user.copy(name = "Updated name"),
                tokens = SessionTokens("new-access", "new-refresh", 2000),
            )
        assertEquals(accountStorageKey(session), accountStorageKey(later))
        assertFalse(accountStorageKey(session).contains("private@"))
        assertFalse(accountStorageKey(session).contains("refresh"))
    }

    @Test
    fun differentAccountsAndAudiencesHaveDifferentOwners() {
        assertNotEquals(
            accountStorageKey(session),
            accountStorageKey(session.copy(user = session.user.copy(id = "account-b"))),
        )
        assertNotEquals(
            accountStorageKey(session),
            accountStorageKey(
                session.copy(
                    binding = session.binding.copy(audience = "https://other.example.invalid/v1/")
                )
            ),
        )
        val left =
            session.copy(
                user = session.user.copy(id = "cd"),
                binding = session.binding.copy(audience = "ab"),
            )
        val right =
            session.copy(
                user = session.user.copy(id = "bcd"),
                binding = session.binding.copy(audience = "a"),
            )
        assertNotEquals(accountStorageKey(left), accountStorageKey(right))
    }
}
