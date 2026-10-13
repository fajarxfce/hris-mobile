@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.fixtures.NativeUiServer
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.SignIn
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.onboarding.domain.repositories.OnboardingRepository
import dev.fajar.starter.securestorage.MemoryCredentialStore
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.SelectWorkspace
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.koin.dsl.module

class WorkspaceStartupRecoveryTest {
    @Test
    fun reopeningAnExpiredEncryptedWorkspaceOfflineShowsRecoveryAndRetryReopensTheSameCompany() =
        runComposeUiTest {
            val directory = Files.createTempDirectory("workspace-startup").toFile()
            val credentials = MemoryCredentialStore()
            val keys = MemoryCredentialStore()
            val offline = AtomicBoolean(false)
            val offlineCalls = AtomicInteger()
            val pauseAccount = AtomicBoolean(false)
            val pendingAccount = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val server =
                NativeUiServer(
                    beforeRequest = { request ->
                        if (offline.get()) {
                            offlineCalls.incrementAndGet()
                            throw IOException("Fixture network is unavailable")
                        }
                        if (
                            request.url.encodedPath == "/api/v1/me" &&
                                pauseAccount.compareAndSet(true, false)
                        ) {
                            pendingAccount.complete(Unit)
                            release.await()
                        }
                    }
                )
            fun createContainer() =
                createAppContainer(
                    createUserPreferences(directory),
                    createAppDatabase(directory),
                    module {
                        single<NotificationPermissionSource> {
                            object : NotificationPermissionSource {
                                override suspend fun check() = NotificationPermission.Granted

                                override suspend fun request() = NotificationPermission.Granted
                            }
                        }
                        single<NotificationDisplaySource> {
                            object : NotificationDisplaySource {
                                override suspend fun show(payload: NotificationPayload) = Unit
                            }
                        }
                        single<PushTokenSource> { UnavailablePushTokenSource() }
                    },
                    environment = AppEnvironment.Dev,
                    platform = AppPlatform.Android,
                    credentials = credentials,
                    workspaceCredentials = keys,
                    http = applicationNetworkModule(AppEnvironment.Dev, server::engine),
                )
            var container = createContainer()
            val owner =
                object : LifecycleOwner {
                    override val lifecycle =
                        LifecycleRegistry.createUnsafe(this).apply {
                            currentState = Lifecycle.State.RESUMED
                        }
                }
            val visible = mutableStateOf(true)
            try {
                val selected = runBlocking {
                    assertIs<AppResult.Success<*>>(
                        container.koin.get<SignIn>()(
                            "alex@example.com",
                            "Fictional-password-123!",
                            UUID.randomUUID().toString(),
                        )
                    )
                    assertIs<AppResult.Success<*>>(
                        container.koin.get<OnboardingRepository>().complete()
                    )
                    val session =
                        assertIs<AppResult.Success<Session?>>(
                                container.koin.get<SessionRepository>().current()
                            )
                            .value!!
                    val workspace =
                        assertIs<AppResult.Success<Workspace>>(
                                container.koin.get<SelectWorkspace>()(
                                    session.id,
                                    NativeUiServer.COMPANY_ONE,
                                )
                            )
                            .value
                    val expired =
                        workspace.copy(
                            admission =
                                workspace.admission.copy(
                                    checkedAt = Clock.System.now() - 2.hours,
                                    expiresAt = Clock.System.now() - 1.hours,
                                )
                        )
                    assertEquals(
                        AppResult.Success(true),
                        container.koin
                            .get<WorkspaceRepository>()
                            .compareAndSet(session.id, workspace, expired),
                    )
                    expired
                }
                container.close()
                offline.set(true)
                container = createContainer()
                setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        if (visible.value)
                            Box(Modifier.size(390.dp, 844.dp)) { StarterApp(container) }
                    }
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Connect to continue").fetchSemanticsNodes().isNotEmpty() &&
                        offlineCalls.get() > 0
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodes(hasText("Try again") and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                onNodeWithText("Verifying account").assertDoesNotExist()
                onNodeWithText("PT Nusa Digital").assertDoesNotExist()
                onNode(hasText("Home") and isSelectable()).assertDoesNotExist()
                assertEquals(
                    AppResult.Success(selected),
                    runBlocking {
                        container.koin.get<WorkspaceRepository>().current(selected.sessionId)
                    },
                )
                offline.set(false)
                pauseAccount.set(true)
                onNodeWithText("Try again").performClick()
                waitUntil(timeoutMillis = 5_000) { pendingAccount.isCompleted }
                onNodeWithText("Connect to continue").assertIsDisplayed()
                onNodeWithText("PT Nusa Digital").assertDoesNotExist()
                onNodeWithText("Try again").assertIsNotEnabled()
                release.complete(Unit)
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodes(hasText("Home") and isSelectable())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                onNodeWithText("Connect to continue").assertDoesNotExist()
                onNodeWithText("Choose a company").assertDoesNotExist()
                onNodeWithText("PT Nusa Digital").assertExists()
                val renewed = runBlocking {
                    assertIs<AppResult.Success<Workspace?>>(
                            container.koin.get<WorkspaceRepository>().current(selected.sessionId)
                        )
                        .value!!
                }
                assertEquals(selected.id, renewed.id)
                assertTrue(renewed.admission.expiresAt > Clock.System.now())
                assertTrue(server.unexpectedPaths.isEmpty(), server.unexpectedPaths.toString())
            } finally {
                release.complete(Unit)
                runOnIdle { visible.value = false }
                waitForIdle()
                runOnIdle { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
                container.close()
                directory.deleteRecursively()
            }
        }
}
