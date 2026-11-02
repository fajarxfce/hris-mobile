@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationAccess
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationCapturedRecoveryTest {
    @Test
    fun capturedAccessObservationSurvivesTokenRotationAndClosesOnReplacementOrDenial() = runTest {
        val f = MessageIntentFixture()
        val scope = f.captureRecoveryScope()
        val original = requireNotNull(f.setup.session)
        val identities = MutableStateFlow<AppResult<Session?>>(AppResult.Success(original))
        val sessions =
            object : SessionRepository by f.setup.sessions {
                override fun observe() = identities
            }
        val workspace =
            object : WorkspaceRepository by f.setup.workspaces {
                override fun observe(sessionId: String): Nothing = error("No selection observation")
            }
        val observe =
            ObserveCollaborationAccess(f.lists, sessions, workspace, f.server, f.build, f.clock)
        val values = mutableListOf<AppResult<CollaborationLocalAccess>>()
        val task = backgroundScope.launch { observe(scope).collect(values::add) }
        runCurrent()
        assertEquals(1, values.size)
        assertEquals(AppResult.Success(scope.access), values.single())
        identities.value =
            AppResult.Success(original.copy(tokens = original.tokens.copy(accessToken = "rotated")))
        runCurrent()
        assertEquals(1, values.size)
        f.gate.value = CollaborationListAdmission("denied", f.denied)
        runCurrent()
        assertEquals(AppResult.Failed(f.denied), values.last())
        f.gate.value = CollaborationListAdmission("admitted", null)
        runCurrent()
        assertEquals(AppResult.Success(scope.access), values.last())
        identities.value = AppResult.Success(original.copy(id = "replacement"))
        runCurrent()
        assertIs<AppResult.Failed>(values.last())
        val count = values.size
        task.cancelAndJoin()
        identities.value = AppResult.Success(original)
        runCurrent()
        assertEquals(count, values.size)
        assertEquals(0, f.messageObservations)
        assertEquals(0, f.setup.calls)
    }
}

internal fun MessageIntentFixture.captureRecoveryScope(): CollaborationLocalScope.Captured {
    val session = requireNotNull(setup.session)
    val scope =
        CollaborationLocalScope.Captured(
            CollaborationLocalAccess(
                session.id,
                server.audience,
                session.user.id,
                requireNotNull(setup.workspace).company.id,
            )
        )
    setup.workspace = null
    setup.beforeWorkspace = { error("Captured recovery cannot read workspace selection") }
    setup.now += 365.days
    return scope
}
