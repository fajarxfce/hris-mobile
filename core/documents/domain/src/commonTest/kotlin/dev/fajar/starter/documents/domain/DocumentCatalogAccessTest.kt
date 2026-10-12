@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.usecases.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class DocumentCatalogAccessTest {
    private val build = AppBuild(AppPlatform.Android, 1, "1.0", null)

    @Test
    fun ownEmploymentReferencesRequireReadAccessWithoutUploadOrPeople() = runTest {
        val f = DocumentFixture()
        val load =
            LoadDocumentEmployments(
                f.repository,
                f.sessionRepository,
                f.workspaceRepository,
                f.clock,
                build,
            )
        val result =
            assertIs<AppResult.Success<DocumentEmploymentSnapshot>>(load(f.workspace.id)).value
        assertEquals(f.query.employmentId, result.page.items.single().id)
        assertEquals(setOf("documents.self.read"), result.access.permissions)
        assertEquals(f.now + 60.seconds, result.expiresAt)
        assertIs<AppResult.Failed>(load(f.workspace.id, "x".repeat(33)))
        assertEquals(1, f.policyReads)
        assertEquals(0, f.evidenceReads)
    }

    @Test
    fun aLateEmploymentPageCannotCrossAChangedCompany() = runTest {
        val f = DocumentFixture()
        val employment = f.employmentCall
        f.employmentCall = {
            f.workspaces.value =
                AppResult.Success(
                    f.workspace.copy(company = f.workspace.company.copy(id = f.evidence.documentId))
                )
            employment()
        }
        val load =
            LoadDocumentEmployments(
                f.repository,
                f.sessionRepository,
                f.workspaceRepository,
                f.clock,
                build,
            )
        assertEquals(
            "client.document_scope_changed",
            assertIs<AppResult.Failed>(load(f.workspace.id)).failure.code,
        )
    }

    @Test
    fun admissionObservationExpiresAndReleasesSourcesWithoutNetworkAcquisition() = runTest {
        val f = DocumentFixture()
        val sessions =
            object : SessionRepository by f.sessionRepository {
                override fun observe() = f.sessions
            }
        val workspaces =
            object : WorkspaceRepository by f.workspaceRepository {
                override fun observe(sessionId: String) = f.workspaces
            }
        val observe = ObserveDocumentAccess(sessions, workspaces, f.clock, build)
        val values = mutableListOf<AppResult<DocumentAccess>>()
        val observation =
            backgroundScope.launch { observe(f.workspace.id).collect { values += it } }
        runCurrent()
        assertIs<AppResult.Success<DocumentAccess>>(values.single())
        f.now += 1.hours
        advanceTimeBy(3_600_001)
        runCurrent()
        assertEquals(
            "client.workspace_expired",
            assertIs<AppResult.Failed>(values.last()).failure.code,
        )
        observation.cancelAndJoin()
        assertEquals(0, f.sessions.subscriptionCount.value)
        assertEquals(0, f.workspaces.subscriptionCount.value)
        assertEquals(0, f.policyReads)
        assertEquals(0, f.evidenceReads)
    }

    @Test
    fun permissionLossInvalidatesCurrentMetadataBeforeTheLeaseExpires() = runTest {
        val f = DocumentFixture()
        val sessions =
            object : SessionRepository by f.sessionRepository {
                override fun observe() = f.sessions
            }
        val workspaces =
            object : WorkspaceRepository by f.workspaceRepository {
                override fun observe(sessionId: String) = f.workspaces
            }
        val values = mutableListOf<AppResult<DocumentAccess>>()
        val observation =
            backgroundScope.launch {
                ObserveDocumentAccess(sessions, workspaces, f.clock, build)(f.workspace.id)
                    .collect { values += it }
            }
        runCurrent()
        f.workspaces.value = AppResult.Success(f.workspace.copy(permissions = emptySet()))
        runCurrent()
        assertEquals("access_denied", assertIs<AppResult.Failed>(values.last()).failure.code)
        observation.cancelAndJoin()
    }
}
