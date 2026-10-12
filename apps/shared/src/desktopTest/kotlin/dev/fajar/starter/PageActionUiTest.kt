package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowSync
import kotlin.test.*
import org.junit.Rule
import org.junit.Test

class PageActionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fixedActionRemainsReachableBelowTheLastRowAtLargeText() {
        var actions = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(Modifier.size(360.dp, 560.dp).testTag("frame")) {
                        AppLazyPage(
                            topBar = { AppTopBar("Requests") },
                            bottomBar = {
                                AppActionBar {
                                    AppButton("New request", { actions++ }, Modifier.fillMaxWidth())
                                }
                            },
                        ) {
                            items(40) { AppText("Request $it", Modifier.heightIn(min = 48.dp)) }
                        }
                    }
                }
            }
        }
        val action = compose.onNode(hasText("New request") and hasClickAction())
        val original = action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        val last =
            compose
                .onNodeWithText("Request 39")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(original, action.fetchSemanticsNode().boundsInRoot)
        assertTrue(last.bottom <= original.top)
        compose.onNodeWithText("Requests").assertIsDisplayed()
        action.performClick()
        compose.runOnIdle { assertEquals(1, actions) }
    }

    @Test
    fun busyIconKeepsItsTargetAndLabelWithoutAllowingAnotherAction() {
        var busy by mutableStateOf(false)
        var actions = 0
        compose.setContent {
            AppTheme {
                AppIconButton(
                    Icons.Regular.ArrowSync,
                    "Refresh",
                    {
                        actions++
                        busy = true
                    },
                    loading = busy,
                )
            }
        }
        val action = compose.onNodeWithContentDescription("Refresh")
        val original = action.fetchSemanticsNode().boundsInRoot
        action.performClick()
        action.assertIsNotEnabled()
        assertEquals(original, action.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { busy = false }
        action.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, actions) }
    }
}
