@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.LocalCompatNavigationEventDispatcherOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fajar.inspector.*
import dev.fajar.starter.app.inspection.NetworkInspectionScreen
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.fixtures.TestBackDispatcher
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class NetworkInspectionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun clearReplacesSelectedIdentityAndBackReturnsFromDetailToList() {
        val inspection = NetworkInspection()
        val owner = InspectionTestOwner()
        val back = TestBackDispatcher()
        runBlocking { record(inspection, "/first") }
        var exits = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalAppLanguage provides AppLanguage.English,
                LocalLifecycleOwner provides owner,
                LocalCompatNavigationEventDispatcherOwner provides back,
            ) {
                AppTheme {
                    Box(Modifier.size(375.dp, 720.dp)) {
                        NetworkInspectionScreen(inspection) { exits++ }
                    }
                }
            }
        }
        try {
            compose.onNodeWithText("/first", substring = true).performClick()
            compose.onNodeWithText("Search requests").assertDoesNotExist()
            compose.runOnIdle { back.pressBack() }
            compose.onNodeWithText("Search requests").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, exits) }
            compose.onNodeWithText("/first", substring = true).performClick()
            compose.runOnIdle {
                runBlocking {
                    inspection.reset()
                    record(inspection, "/replacement")
                }
            }
            compose.onNodeWithText("Search requests").assertIsDisplayed()
            compose.onNodeWithText("/first", substring = true).assertDoesNotExist()
            compose.onNodeWithText("/replacement", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Clear").performClick()
            compose.onNodeWithText("/replacement", substring = true).assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.runOnIdle { assertEquals(1, exits) }
        } finally {
            compose.runOnIdle {
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                back.close()
            }
            runBlocking { inspection.close() }
        }
    }

    @Test
    fun smallScreenWithLargeTextKeepsSearchAndBackUsable() {
        val inspection = NetworkInspection()
        val owner = InspectionTestOwner()
        val back = TestBackDispatcher()
        compose.setContent {
            CompositionLocalProvider(
                LocalAppLanguage provides AppLanguage.Indonesian,
                LocalDensity provides Density(1f, 2f),
                LocalLifecycleOwner provides owner,
                LocalCompatNavigationEventDispatcherOwner provides back,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp)) { NetworkInspectionScreen(inspection) {} }
                }
            }
        }
        try {
            compose
                .onNodeWithContentDescription("Kembali")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(44.dp)
            compose.onNodeWithText("Search requests").assertIsDisplayed()
            compose.onNodeWithText("Bersihkan").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        } finally {
            compose.runOnIdle {
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                back.close()
            }
            runBlocking { inspection.close() }
        }
    }

    private suspend fun record(inspection: NetworkInspection, path: String) {
        val store = checkNotNull(inspection.recorders.value)
        val handle =
            checkNotNull(
                store.begin(
                    NetworkRequest(
                        NetworkSource.KTOR,
                        "GET",
                        NetworkEndpoint("https", "example.invalid", path = path),
                    )
                )
            )
        store.finish(handle, NetworkOutcome.Response(200))
    }
}

private class InspectionTestOwner : LifecycleOwner {
    override val lifecycle =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
}
