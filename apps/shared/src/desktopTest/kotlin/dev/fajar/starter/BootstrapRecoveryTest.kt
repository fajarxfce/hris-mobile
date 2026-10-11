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
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.onboarding.domain.repositories.OnboardingRepository
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.koin.dsl.module

class BootstrapRecoveryTest {
    @Test
    fun pendingBootstrapFailureUpdatesTheRetainedRouteAndRetryCanReachOnboarding() =
        runComposeUiTest {
            val compose = this
            val directory = Files.createTempDirectory("bootstrap-recovery").toFile()
            val repository = PendingOnboardingRepository()
            val container =
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
                )
            container.koin.loadModules(
                listOf(module { single<OnboardingRepository> { repository } })
            )
            val owner = BootstrapLifecycleOwner()
            val visible = mutableStateOf(true)
            try {
                compose.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        Box(Modifier.size(390.dp, 844.dp)) {
                            if (visible.value) StarterApp(container)
                        }
                    }
                }
                compose.waitUntil(timeoutMillis = 5_000) { repository.reads.get() == 1 }
                compose.onNodeWithText("Try again").assertDoesNotExist()
                repository.pending.complete(
                    AppResult.Failed(Failure(FailureKind.Storage, "Fixture storage failed."))
                )
                compose.waitUntil(timeoutMillis = 5_000) {
                    compose
                        .onAllNodesWithText("Local data could not be read or saved.")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText("Try again").performClick()
                compose.waitUntil(timeoutMillis = 5_000) {
                    compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(2, repository.reads.get())
                compose
                    .onNodeWithText("Local data could not be read or saved.")
                    .assertDoesNotExist()
            } finally {
                repository.pending.cancel()
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
                container.close()
                directory.deleteRecursively()
            }
        }
}

private class PendingOnboardingRepository : OnboardingRepository {
    val reads = AtomicInteger()
    val pending = CompletableDeferred<AppResult<Boolean>>()

    override suspend fun isComplete(): AppResult<Boolean> =
        if (reads.incrementAndGet() == 1) pending.await() else AppResult.Success(false)

    override suspend fun complete() = AppResult.Success(Unit)
}

private class BootstrapLifecycleOwner : LifecycleOwner {
    override val lifecycle =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
}
