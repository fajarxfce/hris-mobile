package dev.fajar.starter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.dashboard.domain.config.DashboardFlags
import dev.fajar.starter.dashboard.presentation.home.DashboardTab
import dev.fajar.starter.dashboard.presentation.home.DashboardViewModel
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.featureflags.domain.usecases.RefreshFeatureFlags
import dev.fajar.starter.featureflags.domain.usecases.SetFeatureFlagOverride
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.sync.domain.SyncTask
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

class AppFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun onboardingLoginTabsAndLogout() {
        val directory = Files.createTempDirectory("fluent-ui").toFile()
        val preferences = createUserPreferences(directory)
        val container =
            createAppContainer(
                preferences,
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
        val workScope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
            )
        dev.fajar.starter.app.work.startForegroundSync(container, workScope)
        val generation = mutableStateOf(0)
        try {
            compose.setContent {
                val lifecycleOwner = remember {
                    object : LifecycleOwner {
                        override val lifecycle =
                            LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                    }
                }
                DisposableEffect(lifecycleOwner) {
                    onDispose { lifecycleOwner.lifecycle.currentState = Lifecycle.State.DESTROYED }
                }
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    key(generation.value) {
                        Box(Modifier.size(420.dp, 760.dp)) { StarterApp(container) }
                    }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Your workspace").fetchSemanticsNodes().isNotEmpty()
            }
            capture("onboarding")
            compose.onNodeWithText("Skip").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Use demo account").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(true, runBlocking { preferences.data.first().onboarding_completed })
            capture("login")
            compose.onNodeWithText("Use demo account").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Hide password").assertExists().performClick()
            compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Projects").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle {
                val initialTabProbe =
                    container.koin.get<DashboardViewModel> { parametersOf(DashboardTab.Activity) }
                assertEquals(DashboardTab.Activity, initialTabProbe.state.value.tab)
                ViewModelStore().apply {
                    put("probe", initialTabProbe)
                    clear()
                }
            }
            capture("dashboard")
            compose.runOnIdle { generation.value++ }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Projects").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText("Activity") and hasClickAction()).performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Workspace created").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Save Workspace created").assertExists()
            compose.waitUntil(15_000) {
                compose
                    .onAllNodes(hasText("Load more") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Load more").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Review 5 completed").fetchSemanticsNodes().isNotEmpty()
            }

            val overrideFlag = container.koin.get<SetFeatureFlagOverride>()
            runBlocking { overrideFlag(DashboardFlags.SavedActivities, false) }
            compose.waitUntil(5_000) {
                compose
                    .onAllNodesWithContentDescription("Save Workspace created")
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            compose.onNodeWithText("Workspace created").assertExists()
            runBlocking { overrideFlag(DashboardFlags.SavedActivities, null) }
            compose.waitUntil(5_000) {
                compose
                    .onAllNodesWithContentDescription("Save Workspace created")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val refresh = container.koin.get<RefreshFeatureFlags>()
            assertEquals(1, container.koin.getAll<SyncTask>().count { it === refresh })
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithText("Alex Morgan").assertExists()
            compose.onNodeWithText("Notifications").performClick()
            compose.waitUntil(15_000) {
                compose
                    .onAllNodesWithText("Send test notification")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Send test notification").performClick()
            compose.waitUntil(15_000) {
                compose
                    .onAllNodesWithText("Test notification sent.")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("Files").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose
                    .onAllNodes(hasText("Download sample") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Download sample").performClick()
            compose.onNodeWithText("Upload sample").performClick()
            compose.waitUntil(30_000) {
                compose
                    .onAllNodes(hasText("Completed", substring = true))
                    .fetchSemanticsNodes()
                    .size == 2
            }
            capture("files")
            runBlocking {
                val database = container.koin.get<dev.fajar.starter.database.AppDatabase>()
                val session =
                    (container.koin
                            .get<dev.fajar.starter.identity.domain.repositories.SessionRepository>()
                            .current() as dev.fajar.starter.common.result.AppResult.Success)
                        .value!!
                val rows = database.transfers.observe(session.id).first()
                assertEquals(2, rows.size)
                val downloaded = rows.single { it.direction == "Download" }
                assertEquals(downloaded.size, downloaded.storedBytes)
                val firstChunk = database.transfers.chunk(session.id, downloaded.id, 0)!!
                assertEquals(262_144, firstChunk.bytes.size)
                val readable =
                    container.koin.get<
                            dev.fajar.starter.transfers.domain.usecases.ReadDownloadedFile
                        >()(downloaded.id)
                        .first()
                assertEquals(
                    262_144,
                    (readable as dev.fajar.starter.common.result.AppResult.Success).value.size,
                )
                assertEquals(
                    true,
                    firstChunk.bytes
                        .decodeToString()
                        .startsWith("Compose Fluent Starter sample file."),
                )
                val uploaded = rows.single { it.direction == "Upload" }
                assertEquals(0L, uploaded.storedBytes)
            }
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("Settings").performScrollTo().performClick()
            compose.onNodeWithText("Bahasa Indonesia").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Pengaturan").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Kembali").performClick()
            compose.onNodeWithText("Pengaturan").performScrollTo().performClick()
            compose.onNodeWithText("English").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("Sign out").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Use demo account").fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNodeWithText("Sign in with Demo organization")
                .performScrollTo()
                .performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Projects").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            runBlocking {
                workScope.coroutineContext[kotlinx.coroutines.Job]?.let { job ->
                    job.cancel()
                    job.join()
                }
            }
            container.close()
            directory.deleteRecursively()
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asSkiaBitmap()
        val data = requireNotNull(Image.makeFromBitmap(bitmap).encodeToData())
        val file = File("build/reports/screenshots/$name.png")
        file.parentFile.mkdirs()
        file.writeBytes(data.bytes)
    }
}
