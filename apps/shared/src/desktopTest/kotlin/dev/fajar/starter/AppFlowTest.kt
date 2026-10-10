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
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.storage.PreferenceStore
import java.io.File
import kotlin.test.assertEquals
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class AppFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun onboardingLoginTabsAndLogout() {
        val preferences =
            object : PreferenceStore {
                var completed: Boolean? = null

                override suspend fun readBoolean(key: String) = completed

                override suspend fun writeBoolean(key: String, value: Boolean): Boolean {
                    completed = value
                    return true
                }
            }
        val container = createAppContainer(preferences)
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
            assertEquals(true, preferences.completed)
            capture("login")
            compose.onNodeWithText("Use demo account").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Hide password").assertExists().performClick()
            compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Projects").fetchSemanticsNodes().isNotEmpty()
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
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithText("Alex Morgan").assertExists()
            compose.onNodeWithText("Sign out").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Use demo account").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            container.close()
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
