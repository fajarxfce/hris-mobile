package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.*
import org.junit.Rule
import org.junit.Test

class ContentTabsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectionRemainsControlledAndDisabledTabsDoNotDispatch() {
        val events = mutableListOf<Int>()
        var selected by mutableIntStateOf(1)
        var enabled by mutableStateOf(true)
        compose.setContent {
            AppTheme {
                AppTabs(
                    listOf(AppTabItem(1, "Personal"), AppTabItem(2, "Receipt")),
                    selected,
                    events::add,
                    enabled = enabled,
                )
            }
        }
        compose.onNodeWithText("Personal").assertIsSelected()
        compose.onNodeWithText("Receipt").performClick().assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(listOf(2), events)
            selected = 2
            enabled = false
        }
        compose.onNodeWithText("Receipt").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("Personal").performClick()
        compose.runOnIdle { assertEquals(listOf(2), events) }
    }

    @Test
    fun longLabelsWrapWithoutLosingTheirTouchTargetsAtLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(Modifier.width(320.dp)) {
                        AppTabs(
                            listOf(
                                AppTabItem(1, "Personal documents"),
                                AppTabItem(2, "Payment receipts"),
                            ),
                            1,
                            {},
                        )
                    }
                }
            }
        }
        val first =
            compose
                .onNodeWithText("Personal documents")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val second =
            compose
                .onNodeWithText("Payment receipts")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(first.height >= 48 && second.height >= 48)
        assertTrue(first.right <= second.left)
        assertEquals(first.bottom, second.bottom)
    }
}
