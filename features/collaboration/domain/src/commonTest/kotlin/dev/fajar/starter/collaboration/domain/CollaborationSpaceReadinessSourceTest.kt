package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationSpaceReadinessSourceTest {
    private val parent = CollaborationNativeParent("local", "https://example.test", id(1), id(2))
    private val source =
        CollaborationSpaceRuntimeReady(CollaborationSpaceOwner(parent, id(3)), id(4), id(5))
    private val control =
        CollaborationSpaceReadinessControl(id(6), id(3), id(1), id(2), id(4), Long.MAX_VALUE, 1)
    private val signal = CollaborationSpaceReadinessSignal(source, control)

    @Test
    fun exactLogicalRuntimeDefinesScopeWithoutAnyCompanyOrWallClock() {
        assertEquals(
            CollaborationSpaceReadinessScope(parent, id(3), id(4)),
            collaborationSpaceReadinessScope(signal, parent),
        )
        assertSame(source, signal.source)
        assertEquals("CollaborationSpaceReadinessSignal([redacted])", signal.toString())
        assertEquals(Long.MAX_VALUE, signal.control.publicationRevision)
    }

    @Test
    fun packetCannotChooseAnotherOrganizationAccountNativeParentOrDevice() {
        for (other in
            listOf(
                control.copy(spaceId = id(9)),
                control.copy(accountId = id(9)),
                control.copy(nativeSessionId = id(9)),
                control.copy(deviceId = id(9)),
                control.copy(eventId = "00000000-0000-0000-0000-000000000000"),
                control.copy(publicationRevision = 0),
                control.copy(publishedAtEpochMillis = 0),
            )) assertNull(collaborationSpaceReadinessScope(signal.copy(control = other), parent))
    }

    @Test
    fun queuedSourceCannotFollowAChangedLocalSessionAudienceAccountOrNativeParent() {
        for (other in
            listOf(
                parent.copy(sessionId = "replacement"),
                parent.copy(audience = "https://other.test"),
                parent.copy(accountId = id(9)),
                parent.copy(nativeSessionId = id(9)),
            )) assertNull(collaborationSpaceReadinessScope(signal, other))
        assertNull(
            collaborationSpaceReadinessScope(
                signal.copy(source = source.copy(owner = source.owner.copy(spaceId = id(9)))),
                parent,
            )
        )
        assertNull(
            collaborationSpaceReadinessScope(
                signal.copy(source = source.copy(deviceId = id(9))),
                parent,
            )
        )
    }

    @Test
    fun malformedInstalledOperationCannotBecomeAValidSource() {
        for (operation in
            listOf(
                "",
                "00000000-0000-0000-0000-000000000000",
                "1-1-1-1-1",
                "A".repeat(36),
                id(5) + "\n",
            )) {
            assertNull(
                collaborationSpaceReadinessScope(
                    signal.copy(source = source.copy(operationId = operation)),
                    parent,
                )
            )
        }
        // A different well-formed operation still requires exact live repository admission;
        // wire shape and local scope matching cannot prove that it is installed.
        assertNotNull(
            collaborationSpaceReadinessScope(
                signal.copy(source = source.copy(operationId = id(8))),
                parent,
            )
        )
    }

    private fun id(value: Int) = "12345678-1234-4234-8234-" + value.toString().padStart(12, '0')
}
