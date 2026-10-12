@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchAndFiltersUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchEntryDispatchesOneActionWithoutCreatingAnEditor() {
        var clicks = 0
        compose.setContent {
            AppTheme {
                Box(Modifier.width(320.dp)) { AppSearchEntry("Search employees", { clicks++ }) }
            }
        }
        val entry = compose.onNodeWithContentDescription("Search employees")
        entry.assertHeightIsAtLeast(48.dp).assert(hasSetTextAction().not()).performClick()
        entry.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) {
            it()
        }
        entry.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(2, clicks) }
    }

    @Test
    fun initialSearchFocusDoesNotReturnOnEditsAndIsReleasedWithTheField() {
        var visible by mutableStateOf(true)
        var value by mutableStateOf("")
        compose.setContent {
            AppTheme {
                if (visible)
                    AppSearchField(value, { value = it }, "Search", "Clear", autoFocus = true)
                else AppText("Closed")
            }
        }
        val field = compose.onNodeWithContentDescription("Search")
        field.assertIsFocused().performTextInput("People")
        field.performImeAction()
        field.assertIsNotFocused()
        compose.runOnIdle { value = "Engineering" }
        field.assertIsNotFocused()
        compose.runOnIdle { visible = false }
        field.assertDoesNotExist()
        compose.runOnIdle { visible = true }
        field.assertIsFocused()
    }

    @Test
    fun searchHasAnAccessibleClearActionAndSupportsLargeTextWithoutASecondHeader() {
        val value = mutableStateOf("")
        val enabled = mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(Modifier.width(320.dp).padding(16.dp).testTag("frame")) {
                        AppSearchField(
                            value.value,
                            { value.value = it },
                            "Search",
                            "Clear search",
                            enabled = enabled.value,
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        compose
            .onNodeWithContentDescription("Search")
            .assertHeightIsAtLeast(48.dp)
            .performTextInput("Engineering")
        compose
            .onNodeWithContentDescription("Clear search")
            .assertWidthIsEqualTo(48.dp)
            .performClick()
        compose.runOnIdle { assertEquals("", value.value) }
        compose.onNodeWithContentDescription("Search").performTextInput("People")
        val field = compose.onNodeWithContentDescription("Search").fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(field.left >= frame.left && field.right <= frame.right)
        compose.onNodeWithContentDescription("Search").performImeAction()
        compose.onNodeWithContentDescription("Search").assertIsNotFocused()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithContentDescription("Clear search").assertIsNotEnabled()
    }

    @Test
    fun filterPickerOwnsFocusAndClosesWithoutChangingTheSelection() {
        val visible = mutableStateOf(false)
        val selected = mutableStateOf("All departments")
        compose.setContent {
            AppTheme {
                Column(Modifier.width(320.dp).padding(16.dp)) {
                    AppFilterChip(
                        selected.value,
                        selected.value != "All departments",
                        { visible.value = true },
                    )
                    if (visible.value)
                        AppDialog("Department", "Close", { visible.value = false }) {
                            AppRadioRow(
                                "Engineering",
                                selected.value == "Engineering",
                                {
                                    selected.value = "Engineering"
                                    visible.value = false
                                },
                            )
                        }
                }
            }
        }
        compose.onNodeWithText("All departments").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Engineering").performClick()
        compose.onNodeWithText("Engineering").assertIsSelected().performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Engineering", selected.value) }
        compose.onNodeWithText("Engineering").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus
        ) {
            it()
        }
        compose.onNodeWithText("Engineering").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
    }
}
