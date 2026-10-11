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
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Home
import io.github.composefluent.icons.regular.Mail
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class NavigationBadgeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun bottomBadgeKeepsItsFullAccessibleCountAndTabActionAtLargeTextSizes() {
        val count = mutableStateOf(126L)
        val selected = mutableStateOf("Home")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(Modifier.width(320.dp)) {
                        AppBottomNavigation(
                            listOf(
                                AppNavigationItem("Home", "Home", Icons.Regular.Home),
                                AppNavigationItem(
                                    "Inbox",
                                    "Inbox",
                                    Icons.Regular.Mail,
                                    badge = { AppCountBadge(count.value, "${count.value} unread") },
                                ),
                            ),
                            selected.value,
                            { selected.value = it },
                        )
                    }
                }
            }
        }
        compose
            .onNode(hasText("Inbox") and hasContentDescription("126 unread"))
            .assertHeightIsAtLeast(48.dp)
            .performClick()
            .assertIsSelected()
        compose.onNodeWithText("99+", useUnmergedTree = true).assertExists()
        compose.runOnIdle { count.value = 0 }
        compose.onNodeWithContentDescription("126 unread").assertDoesNotExist()
        compose.onNode(hasText("Home") and isSelectable()).performClick()
        compose.runOnIdle { assertEquals("Home", selected.value) }
    }

    @Test
    fun sideNavigationRetainsTheBadgeInRailAndSidebarLayouts() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            AppTheme {
                AppSideNavigation(
                    listOf(
                        AppNavigationItem(
                            "Inbox",
                            "Inbox",
                            Icons.Regular.Mail,
                            badge = { AppCountBadge(4, "4 unread") },
                        )
                    ),
                    "Inbox",
                    {},
                    expanded.value,
                )
            }
        }
        compose.onNode(hasText("Inbox") and hasContentDescription("4 unread")).assertIsSelected()
        compose.runOnIdle { expanded.value = true }
        compose.onNode(hasText("Inbox") and hasContentDescription("4 unread")).assertIsSelected()
    }
}
